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
  disbursement history."
  (:require [credit.facts :as facts]
            [credit.registry :as registry]
            [credit.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Disbursing real loan funds is the ONE real-world actuation event this
  actor performs -- a single-member set, matching `cloud-itonami-isic-
  6511`'s/`6621`'s/`6629`'s/`6612`'s single-actuation shape."
  #{:actuation/disburse-loan})

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
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (application-not-approved-violations request st)
                           (affordability-exceeded-violations request st)
                           (double-disbursement-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
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
