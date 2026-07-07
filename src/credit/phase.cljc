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
  itself (see `credit.store`'s own docstring).")

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
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Credit Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
