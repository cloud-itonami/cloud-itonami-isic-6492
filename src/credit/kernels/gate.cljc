(ns credit.kernels.gate
  "Safety kernel for the credit governor + phase gate — the decision
  CORE of `credit.governor/check` and `credit.phase/gate`.

  **The rules are not in this file.** They are in
  `src/credit/kernels/gate.kotoba`, they ship compiled as
  `credit.kernels.gate-kir`, and every function below that names a
  threshold or resolves a code executes that artifact through
  `credit.kernels.kotoba-oracle`. What is left here is the half that is
  not a decision: putting nine host flags into the record the core
  declares, and handing the answer back as an integer.

  That is a change of authority, not of tidiness. Until 2026-08-12 this
  namespace WAS the decision and `wasm/credit_verdict.kotoba` /
  `wasm/credit_phase.kotoba` were checked replicas of it, pinned by a
  test. Two implementations bound by a test are still two
  implementations, and this one was the one that ran (ADR-2608112100).
  The thresholds made that concrete: the `.kotoba` subset those two files
  are written in has no top-level `def`, so both thresholds were typed
  into their bodies, and editing the `def` here left them deciding on
  the old number with no diff and no failing test
  (ADR-2608120200 §2). Both constants are now 0-arity exports of the
  core, ASKED FOR rather than restated — see below.

  ## Wire codes

    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    afford      total = existing-debt + requested-amount, income =
                annual-income (same integer currency units). The
                debt-to-income ceiling is compared EXACTLY in integers:
                exceeded iff 100*total > ceiling*income — no floating-
                point ratio ever enters the kernel. income < 1 or total < 0
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
  (:require [credit.kernels.kotoba-oracle :as oracle]))

;; ------------------------- host combinators ------------------------
;; Boolean plumbing with no threshold and no domain rule in it. Kept
;; here because the battery below is written in terms of them and
;; because there is nothing in them for the core to own. `norm-flag` is
;; NOT among them: "only exact 0 counts as no" is the fail-closed rule
;; itself, so it lives in the core and is asked for.

(defn not-flag [a] (if (= a 0) 1 0))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))
(defn or5 [a b c d e] (or2 (or3 a b c) (or2 d e)))

(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'.
  Decided by the shipped core."
  [a]
  (oracle/call-i64 :gate 'norm-flag [a]))

;; ---------------------------- thresholds ---------------------------
;; Read out of the shipped artifact at load, not written down. This is
;; the difference between a gate that keeps two numbers equal and a
;; delegation that only has one number: there is no literal here for an
;; edit to `gate.kotoba` to disagree with.

(def confidence-floor-x100
  "The confidence floor the shipped core decides by, x100."
  (oracle/call-i64 :gate 'confidence-floor-x100 []))

(def affordability-ceiling-x100
  "The debt-to-income ceiling the shipped core decides by, x100."
  (oracle/call-i64 :gate 'affordability-ceiling-x100 []))

;; --------------------------- governor core -------------------------

(def ^:private proposal-schema
  "The record `gate.kotoba` declares for a governor proposal. Named here,
  shaped there: `credit.kernels.kotoba-oracle/record` resolves it out of
  the artifact and fills the fields in DECLARED order, so a field added
  or moved in the core does not need a second edit here."
  :credit.kernels/proposal)

(defn confidence-low
  "1 when the advisor confidence requires a human look."
  [x100]
  (oracle/call-i64 :gate 'confidence-low [x100]))

(defn affordability-exceeded
  "1 when the application's back-end debt-to-income ratio strictly
  exceeds the affordability ceiling."
  [applicable total income]
  (oracle/call-i64 :gate 'affordability-exceeded [applicable total income]))

(defn- proposal
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income confidence-x100 actuation]
  (oracle/record :gate proposal-schema
                 {:spec-missing spec-missing
                  :evidence-incomplete evidence-incomplete
                  :not-approved not-approved
                  :double-disb double-disb
                  :afford-applicable afford-applicable
                  :afford-total afford-total
                  :afford-income afford-income
                  :confidence-x100 confidence-x100
                  :actuation actuation}))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation is present:
  spec-basis missing / required lending evidence incomplete /
  application never approved / double disbursement / debt-to-income
  affordability ceiling exceeded.

  The core reads a whole proposal record, of which this question uses
  seven fields; the two it does not read (`:confidence-x100`,
  `:actuation`) are passed as 0. That the core really does ignore them
  is pinned by `credit.kernels.kotoba-oracle-test`, not asserted here."
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income]
  (oracle/i64-value
   (oracle/call :gate 'hard-violation
                [(proposal spec-missing evidence-incomplete not-approved
                           double-disb afford-applicable afford-total
                           afford-income 0 0)])))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income confidence-x100 actuation]
  (oracle/i64-value
   (oracle/call :gate 'verdict-code
                [(proposal spec-missing evidence-incomplete not-approved
                           double-disb afford-applicable afford-total
                           afford-income confidence-x100 actuation)])))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column)."
  [phase op]
  (oracle/call-i64 :gate 'op-write-enabled [phase op]))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly one cell is ever 1: phase 3 x :application/intake.
  op 5 (:loan/disburse) AND op 4 (:loan/approve) are 0 at every phase
  — permanent structural facts, not rollout milestones."
  [phase op]
  (oracle/call-i64 :gate 'op-auto-enabled [phase op]))

(defn phase-disposition
  "Final disposition code from phase, op code and the governor's
  disposition code."
  [phase op governor-disposition]
  (oracle/call-i64 :gate 'phase-disposition [phase op governor-disposition]))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (oracle/call-i64 :gate 'phase-reason [phase op governor-disposition]))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.
;; Every case now runs through the shipped core, so this doubles as the
;; broadest statement of what that core answers.

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
