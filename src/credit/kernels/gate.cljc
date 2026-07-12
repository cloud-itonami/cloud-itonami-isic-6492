(ns credit.kernels.gate
  "Safety kernel for the credit governor + phase gate — the decision
  CORE of `credit.governor/check` and `credit.phase/gate`, extracted
  into the safe-kotoba subset (cloud-itonami kernels discipline,
  ADR-0016 / superproject ADR-2607101200), the credit-granting sibling
  of `cloud-itonami-isic-6511`'s `underwriting.kernels.gate`.

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`credit.governor`, `credit.phase`) reduce their inputs to
  flags/codes at the boundary and map the result codes back to
  keywords. `.kotoba`/wasm emission is deliberately NOT wired yet
  (owner decision 2026-07-12: ClojureScript + kotoba-datomic first);
  staying inside the subset is what keeps that door open without a
  rewrite. (The affordability check ALREADY has a `.kotoba`-emitted
  wasm twin in `wasm/affordability.kotoba` — this kernel restates the
  same exact-integer comparison as the in-process deciding copy.)

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    afford      total = existing-debt + requested-amount, income =
                annual-income (same integer currency units). The
                debt-to-income ceiling (0.43) is compared EXACTLY in
                integers: exceeded iff 100*total > 43*income
                (affordability-ceiling-x100 = 43) — no floating-point
                ratio ever enters the kernel. income < 1 or total < 0
                (impossible per `credit.registry`'s validation) count
                as exceeded (fail-closed).
    op          0 read (reserved — this actor has NO read ops; the
                façade never emits 0; kept for fleet-wide code parity)
                1 :application/intake      2 :jurisdiction/assess
                3 :creditworthiness/screen 4 :loan/approve
                5 :loan/disburse           6+ unknown write (never enabled)
    phase       0..3 (anything else: no writes at all — the façade
                normalizes unknown phases to its own default BEFORE the
                kernel, so an out-of-range phase reaching the kernel is
                a bug and fails closed)
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    disposition 0 commit  1 escalate  2 hold
    reason      0 none  1 phase-disabled  2 phase-approval

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. `:loan/disburse` (op 5)
  and `:loan/approve` (op 4) are auto-enabled at NO phase — the same
  structural invariants the phase table and the governor's actuation
  gate state independently."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))
(defn or5 [a b c d e] (or2 (or3 a b c) (or2 d e)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(def affordability-ceiling-x100 43)

(defn affordability-exceeded
  "1 when the application's back-end debt-to-income ratio strictly
  exceeds the affordability ceiling. `applicable` is 0 when the op
  carries no affordability check (norm-flag, fail-closed: any non-0
  applies the check). The comparison (total/income) > 43/100 is done
  EXACTLY in integer arithmetic as 100*total > 43*income, so the
  kernel and the façade's double ratio can never disagree at the
  boundary. Fail-closed ranges: non-positive income or negative total
  would bypass the ceiling, so they count as exceeded."
  [applicable total income]
  (if (= (norm-flag applicable) 0)
    0
    (if (< income 1)
      1
      (if (< total 0)
        1
        (if (< (* affordability-ceiling-x100 income) (* 100 total)) 1 0)))))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation is present:
  spec-basis missing / required lending evidence incomplete /
  application never approved / double disbursement / debt-to-income
  affordability ceiling exceeded."
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income]
  (or5 (norm-flag spec-missing)
       (norm-flag evidence-incomplete)
       (norm-flag not-approved)
       (norm-flag double-disb)
       (affordability-exceeded afford-applicable afford-total afford-income)))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income confidence-x100 actuation]
  (if (= 1 (hard-violation spec-missing evidence-incomplete not-approved
                           double-disb afford-applicable afford-total
                           afford-income))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column)."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 0)))
      (if (= phase 3)
        (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 (if (= op 4) 1 (if (= op 5) 1 0)))))
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly one cell is ever 1: phase 3 x :application/intake.
  op 5 (:loan/disburse) AND op 4 (:loan/approve) are 0 at every phase
  — permanent structural facts, not rollout milestones."
  [phase op]
  (if (= phase 3) (if (= op 1) 1 0) 0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `credit.phase/gate`: governor
  hold always wins; reads pass through; a write not enabled at this
  phase holds; a governor-clean write without auto rights escalates;
  otherwise the governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= op 0)
      governor-disposition
      (if (= 0 (op-write-enabled phase op))
        2
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 1)
          governor-disposition)))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= op 0)
      0
      (if (= 0 (op-write-enabled phase op))
        1
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 2)
          0)))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict [spec evid napp dbl aapp atot ainc conf act expected]
  (if (= (verdict-code spec evid napp dbl aapp atot ainc conf act) expected) 1 0))

(defn check-afford [applicable total income expected]
  (if (= (affordability-exceeded applicable total income) expected) 1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(def battery-case-count 52)

(defn battery-pass-count []
  (+
   ;; -- verdict: each hard check dominates alone (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 4301 10000 100 0 2)
   ;; -- verdict: hard combos still hard-hold
   (check-verdict 1 1 1 1 1 4301 10000 100 0 2)
   (check-verdict 1 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 1 1 0 0 0 0 100 0 2)
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags normalize to violation (fail-closed)
   (check-verdict 7 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 100 9 1)
   (check-verdict 0 0 0 0 5 4301 10000 100 0 2)
   ;; -- affordability: exact-integer ceiling boundary (43/100)
   (check-afford 1 4300 10000 0)
   (check-afford 1 4301 10000 1)
   (check-afford 1 4299 10000 0)
   (check-afford 1 43 100 0)
   (check-afford 1 44 100 1)
   ;; -- affordability: not applicable / fail-closed ranges
   (check-afford 0 4301 10000 0)
   (check-afford 1 0 0 1)
   (check-afford 1 100 -5 1)
   (check-afford 1 -1 100 1)
   (check-afford 1 0 1 0)
   ;; -- phase: governor hold always wins
   (check-phase 3 1 2 2 0)
   ;; -- phase: reads (reserved op 0) pass through every disposition
   (check-phase 0 0 0 0 0)
   (check-phase 0 0 1 1 0)
   (check-phase 1 0 1 1 0)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 2 4 0 2 1)
   (check-phase 2 5 0 2 1)
   (check-phase 3 6 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 2 0 1 2)
   (check-phase 2 3 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 4 0 1 2)
   (check-phase 3 5 0 1 2)
   ;; -- phase: the single auto cell
   (check-phase 3 1 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 1 1 1 0)
   (check-phase 2 1 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)))
