(ns credit.phase
  "Phase 0->3 staged rollout -- the credit-granting analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- application intake allowed, every
                                 write needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment +
                                 creditworthiness screening writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:application/intake` (no capital
                                 risk yet) may auto-commit. `:loan/
                                 approve`/`:loan/disburse` NEVER auto-
                                 commit, at any phase.

  `:loan/disburse` is deliberately ABSENT from every phase's `:auto`
  set, including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Disbursing real loan funds is the ONE
  real-world legal/financial act this actor performs; it is always a
  human lender's call. `credit.governor`'s `:actuation/disburse-loan`
  high-stakes gate enforces the same invariant independently -- two
  layers, not one, agree on this. `:loan/approve` is likewise never
  auto-eligible, at any phase -- unlike sibling actors' `:*/file` ops
  (which normalize an already-given fact with no real decision to
  make), approval is a genuine underwriting DECISION, the same posture
  every sibling's jurisdiction-assessment op has (always escalate,
  never auto), even though approval itself moves no capital. Unlike
  every prior actor in this fleet, phase 3's `:auto` set here has only
  ONE member (`:application/intake`) -- this domain has no separate
  no-capital-risk 'file' lifecycle distinct from the application
  itself (see `credit.store`'s own docstring).

  The decision core is delegated to the safety kernel
  `credit.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own
  battery and the parity matrix in `credit.kernels.gate-test` pin the
  two representations together."
  (:require [credit.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:application/intake :jurisdiction/assess :creditworthiness/screen
                 :loan/approve :loan/disburse})

;; NOTE the invariant: `:loan/disburse` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                                        :auto #{}}
   1 {:label "assisted-intake" :writes #{:application/intake}                                                     :auto #{}}
   2 {:label "assisted-assess" :writes #{:application/intake :jurisdiction/assess :creditworthiness/screen}       :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:application/intake}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. Unknown ops map to 6 (unknown write) — the
  kernel never write-enables it, so an unrecognized op fails closed to
  HOLD exactly as the old set-membership logic did. Code 0 (read) is
  reserved for fleet-wide parity: this actor's `read-ops` is empty, so
  no op ever maps to it."
  [op]
  (cond
    (contains? read-ops op)             0
    (= op :application/intake)          1
    (= op :jurisdiction/assess)         2
    (= op :creditworthiness/screen)     3
    (= op :loan/approve)                4
    (= op :loan/disburse)               5
    :else                               6))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:loan/disburse` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a Credit Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
