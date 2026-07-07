(ns credit.registry
  "Pure-function loan-disbursement record construction -- an append-
  only lending book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a loan-disbursement reference number --
  every lender/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `credit.facts` uses.

  `compute-debt-to-income-ratio` and `affordability-ceiling` are a
  REAL, simplified affordability check (see the fn's own docstring for
  the honest simplification it makes vs. a real underwriting model),
  not an invented placeholder -- `affordability-ceiling` (0.43) mirrors
  the 43% back-end debt-to-income ratio the U.S. Ability-to-Repay /
  Qualified Mortgage rule (Regulation Z, 12 CFR §1026.43) used as its
  general qualifying threshold, a REAL, well-known regulatory reference
  point, cited as an honest starting point rather than dressed up as a
  universal legal requirement for every jurisdiction in `credit.
  facts/catalog`.

  Unlike every other `compute-*` function in this fleet, `compute-
  debt-to-income-ratio`'s inputs (`:existing-debt` / `:annual-income` /
  `:requested-amount`) are PERMANENT ground-truth fields already
  persisted on the application -- there is no separate claimed figure
  to independently re-derive and compare against (contrast `cloud-
  itonami-isic-6629`'s/`6520`'s/`6820`'s/`6612`'s exact-match checks,
  which compare a recompute against something a proposal CLAIMED).
  `credit.governor`'s `affordability-exceeded-violations` therefore
  recomputes straight from the application every time it applies,
  regardless of which op is checking it -- see that check's own
  docstring for why this needs no stored-verdict lookup at all.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any loan-servicing/banking system. It builds the RECORD a
  lender would keep, not the act of disbursing the loan itself (that
  is `credit.operation`'s `:loan/disburse`, always human-gated -- see
  README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed lender's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(def affordability-ceiling
  "A REAL, well-known back-end debt-to-income ratio ceiling (0.43),
  mirroring the U.S. Ability-to-Repay/Qualified Mortgage rule's general
  qualifying threshold (Regulation Z, 12 CFR §1026.43) -- an honest
  starting reference point, NOT a claim that every jurisdiction in
  `credit.facts/catalog` legally mandates this exact number. A real
  underwriting model additionally weighs credit score, loan purpose,
  collateral and payment history; this R0 checks only the debt-to-
  income ratio (see `compute-debt-to-income-ratio`)."
  0.43)

(defn compute-debt-to-income-ratio
  "Pure computation of an application's back-end debt-to-income ratio:
  (existing-debt + requested-amount) / annual-income. A REAL,
  simplified affordability formula (see ns docstring for what a full
  underwriting model additionally considers: credit score, loan
  purpose, collateral, payment history)."
  [{:keys [existing-debt requested-amount annual-income]}]
  (when-not (pos? annual-income)
    (throw (ex-info "compute-debt-to-income-ratio: annual-income must be > 0" {})))
  (when (neg? existing-debt)
    (throw (ex-info "compute-debt-to-income-ratio: existing-debt must be >= 0" {})))
  (when (neg? requested-amount)
    (throw (ex-info "compute-debt-to-income-ratio: requested-amount must be >= 0" {})))
  (/ (+ (double existing-debt) (double requested-amount)) (double annual-income)))

(defn register-loan-disbursement
  "Validate + construct the LOAN-DISBURSEMENT registration DRAFT -- the
  lender's own legal act of disbursing real loan funds to an applicant.
  Pure function -- does not touch any real loan-servicing/banking
  system; it builds the RECORD a lender would keep. `credit.governor`
  independently re-verifies the application's own debt-to-income ratio
  against `affordability-ceiling`, and blocks a double-disbursement of
  the same application, before this is ever allowed to commit."
  [application-id disbursed-amount jurisdiction sequence]
  (when-not (and application-id (not= application-id ""))
    (throw (ex-info "loan-disbursement: application_id required" {})))
  (when (neg? disbursed-amount)
    (throw (ex-info "loan-disbursement: disbursed-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "loan-disbursement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "loan-disbursement: sequence must be >= 0" {})))
  (let [disbursement-number (str (str/upper-case jurisdiction) "-LOAN-" (zero-pad sequence 6))
        record {"record_id" disbursement-number
                "kind" "loan-disbursement-draft"
                "application_id" application-id
                "disbursed_amount" disbursed-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "disbursement_number" disbursement-number
     "certificate" (unsigned-certificate "LoanDisbursementCertificate" disbursement-number disbursement-number)}))

(defn append
  "Append a loan-disbursement record, returning a NEW list (never
  mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
