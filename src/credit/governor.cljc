(ns credit.governor
  "Credit Governor -- the independent compliance layer that earns the
  Credit-LLM the right to commit. The LLM has no notion of
  jurisdictional truth-in-lending disclosure law, whether an
  application is actually approved before a loan is disbursed, whether
  an applicant's own debt-to-income ratio actually exceeds a
  responsible-lending ceiling, or when an act stops being a draft and
  becomes a real-world loan disbursement, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD -- the
  credit-granting analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Four checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete truth-in-lending
  evidence, disbursing a loan that was never approved, or an
  application whose own debt-to-income ratio exceeds the affordability
  ceiling). The confidence/actuation gate is SOFT: it asks a human to
  look (low confidence / actuation), and the human may approve -- but
  see `credit.phase`: for `:stake :actuation/disburse-loan` (a real
  loan disbursement) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`credit.
                                       facts`), or invent one? Unlike
                                       `pension.governor`'s/`realty.
                                       governor`'s/`brokerage.
                                       governor`'s `:*-missing`-shaped
                                       ops, `:loan/disburse` acts
                                       directly on a pre-seeded
                                       application (see `credit.
                                       store`'s own docstring) -- there
                                       is no 'application is missing'
                                       failure mode to guard against
                                       here, so this check needs no
                                       proactive existence-guard.
    2. Evidence incomplete         -- for `:loan/disburse`, are the
                                       jurisdiction's required loan-
                                       application/income-verification/
                                       disclosure docs actually
                                       satisfied?
    3. Application not approved    -- for `:loan/disburse`, has the
                                       referenced application actually
                                       been approved (`:status`
                                       `:approved` OR `:disbursed` --
                                       see the check's own docstring
                                       for why `:disbursed` also
                                       counts, a real bug found and
                                       fixed by demo verification)? A
                                       loan cannot be disbursed against
                                       an application that was never
                                       underwritten.
    4. Affordability exceeded      -- for `:creditworthiness/screen` OR
                                       `:loan/disburse`, does the
                                       application's OWN debt-to-income
                                       ratio (`credit.registry/compute-
                                       debt-to-income-ratio`, computed
                                       straight from the application's
                                       permanent `:existing-debt`/
                                       `:requested-amount`/`:annual-
                                       income` fields) exceed `credit.
                                       registry/affordability-
                                       ceiling`? Unlike every other
                                       unconditional-evaluation check in
                                       this fleet (`casualty.governor/
                                       sanctions-violations`'s three
                                       reuses, `brokerage.governor/
                                       suitability-failure-violations`),
                                       this check needs NO proposal
                                       inspection and NO stored-verdict
                                       lookup at all -- its inputs are
                                       permanent ground-truth fields
                                       already on the application, so it
                                       simply recomputes from the
                                       application every time it
                                       applies, for EITHER op.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:loan/disburse` (a
                                       REAL financial act) -> escalate.

  One more guard, double-disbursement prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `double-disbursement-violations` refuses to
  disburse the SAME application twice, off this actor's own
  disbursement history.

  The decision itself is delegated to the safety kernel
  `credit.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps gathering the human-readable violation
  evidence and maps the kernel's verdict code back to keywords."
  (:require [credit.facts :as facts]
            [credit.kernels.gate :as gate]
            [credit.registry :as registry]
            [credit.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `credit.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal
  by `credit.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Disbursing real loan funds is the ONE real-world actuation event this
  actor performs -- a single-member set, matching `cloud-itonami-isic-
  6511`'s/`6621`'s/`6629`'s/`6612`'s single-actuation shape."
  #{:actuation/disburse-loan})

(def ^:private affordability-checked-ops
  "Ops that carry the debt-to-income affordability check -- the same
  set `affordability-exceeded-violations` guards on."
  #{:creditworthiness/screen :loan/disburse})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:loan/disburse`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's truth-in-lending disclosure requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :loan/disburse} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:loan/disburse`, the jurisdiction's required loan-application/
  income-verification/disclosure evidence must actually be satisfied
  -- do not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :loan/disburse)
    (let [a (store/application st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(借入申込書/契約締結前交付書面等)が充足していない状態での融資実行提案"}]))))

(defn- application-not-approved-violations
  "For `:loan/disburse`, the referenced application must actually have
  been approved at some point -- a loan cannot be disbursed against an
  application that was never underwritten. UNLIKE `reinsurance.
  governor/treaty-not-bound-violations`/`pension.governor/member-not-
  in-payout-violations`/`realty.governor/property-not-under-
  management-violations`/`brokerage.governor/account-not-active-
  violations` (whose status genuinely never advances past the checked
  value), THIS application's status DOES advance past `:approved` to
  `:disbursed` after a successful disbursement -- the SAME status-
  lifecycle trap `cloud-itonami-isic-6622`'s `placement-not-bound-
  violations` originally hit. Checking `(= :approved status)` directly
  would spuriously re-fire on a double-disbursement attempt (status is
  `:disbursed`, not `:approved`, by then). Fixed the same way `6622`
  fixed it: check for a status VALUE SET that covers every state
  reachable only via approval (`:approved` and `:disbursed` both imply
  the application was approved -- `:disbursed` is only reachable by
  first passing this very check), not a single terminal value."
  [{:keys [op subject]} st]
  (when (= op :loan/disburse)
    (when-not (contains? #{:approved :disbursed} (:status (store/application st subject)))
      [{:rule :application-not-approved
        :detail (str subject " は承認(approved)されていないため、融資は実行できない")}])))

(defn- affordability-exceeded-violations
  "For `:creditworthiness/screen` OR `:loan/disburse`, independently
  recompute the application's debt-to-income ratio via `credit.
  registry/compute-debt-to-income-ratio` and refuse if it exceeds
  `credit.registry/affordability-ceiling`. Needs no proposal inspection
  and no stored-verdict lookup -- its inputs are permanent ground-truth
  fields already on the application (see this ns's own docstring)."
  [{:keys [op subject]} st]
  (when (contains? #{:creditworthiness/screen :loan/disburse} op)
    (let [a (store/application st subject)
          dti (registry/compute-debt-to-income-ratio a)]
      (when (> dti registry/affordability-ceiling)
        [{:rule :affordability-exceeded
          :detail (str subject " の負債収入比率(" dti ")が上限(" registry/affordability-ceiling ")を超過している")}]))))

(defn- double-disbursement-violations
  "For `:loan/disburse`, refuses to disburse the SAME application
  twice, off this actor's own application status -- needs no upstream
  comparison at all."
  [{:keys [op subject]} st]
  (when (= op :loan/disburse)
    (when (store/application-already-disbursed? st subject)
      [{:rule :double-disbursement
        :detail (str subject " は既に融資実行済み")}])))

(defn check
  "Censors a Credit-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [spec-v (spec-basis-violations request proposal)
        evid-v (evidence-incomplete-violations request st)
        napp-v (application-not-approved-violations request st)
        affd-v (affordability-exceeded-violations request st)
        dbl-v  (double-disbursement-violations request st)
        hard (into [] (concat spec-v evid-v napp-v affd-v dbl-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        ;; Affordability bridge: the kernel re-decides the ceiling from
        ;; the application's raw integer fields (100*total > 43*income,
        ;; EXACT integer arithmetic -- no float ratio crosses the
        ;; boundary), matching `affordability-exceeded-violations`'s
        ;; human-readable double compare at every representable input.
        afford? (boolean (affordability-checked-ops (:op request)))
        affd-app (when afford? (store/application st (:subject request)))
        ;; The decision itself is delegated to the safety kernel
        ;; (credit.kernels.gate, integer-coded fail-closed core); this
        ;; façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (if (seq spec-v) 1 0)
                                (if (seq evid-v) 1 0)
                                (if (seq napp-v) 1 0)
                                (if (seq dbl-v) 1 0)
                                (if afford? 1 0)
                                (if afford?
                                  (+ (:existing-debt affd-app)
                                     (:requested-amount affd-app))
                                  0)
                                (if afford? (:annual-income affd-app) 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
