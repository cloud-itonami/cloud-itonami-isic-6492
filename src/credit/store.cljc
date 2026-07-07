(ns credit.store
  "SSoT for the credit-granting actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/credit/store_contract_test.clj), which is the whole point: the
  actor, the Credit Governor and the audit ledger never know which SSoT
  they run on.

  Unlike `pension.store`/`realty.store`/`brokerage.store` (which all
  have a dynamically-filed sub-record -- a disbursement, a fee request,
  an order -- distinct from the entity it's filed against), this Store
  has only ONE entity: the loan APPLICATION itself. Disbursement acts
  directly on a pre-seeded application, the same simpler shape
  `casualty.store`'s `:policy/mark-bound` and `reinsurance.store`'s
  `:treaty/mark-bound` use -- there is no 'application-missing' concept
  to guard against, because the application always exists by the time
  any op runs against it.

  The ledger stays append-only on every backend: 'which application was
  screened for creditworthiness, which loan was approved and
  disbursed, on what jurisdictional basis, approved by whom' is always
  a query over an immutable log -- the audit trail a borrower trusting
  a lender with a loan needs, and the evidence an operator needs if a
  disbursement is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [credit.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (application [s id])
  (all-applications [s])
  (creditworthiness-of [s application-id] "committed creditworthiness screening verdict for an application, or nil")
  (assessment-of [s application-id] "committed jurisdiction disclosure/registration assessment, or nil")
  (ledger [s])
  (disbursement-history [s] "the append-only loan-disbursement history (credit.registry drafts)")
  (next-sequence [s jurisdiction] "next disbursement-number sequence for a jurisdiction")
  (application-already-disbursed? [s application-id] "has this application already been disbursed?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-applications [s applications] "replace/seed the application directory (map id->application)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained application set so the actor + tests run
  offline."
  []
  {:applications
   {"app-1" {:id "app-1" :applicant "田中 一郎" :requested-amount 500000
             :annual-income 4000000 :existing-debt 500000 :credit-score 720
             :jurisdiction "JPN" :status :intake}
    "app-2" {:id "app-2" :applicant "J. Smith" :requested-amount 100000
             :annual-income 3000000 :existing-debt 200000 :credit-score 680
             :jurisdiction "ATL" :status :intake}
    "app-3" {:id "app-3" :applicant "鈴木 花子" :requested-amount 3000000
             :annual-income 4000000 :existing-debt 500000 :credit-score 640
             :jurisdiction "JPN" :status :intake}
    "app-4" {:id "app-4" :applicant "佐藤 次郎" :requested-amount 500000
             :annual-income 4000000 :existing-debt 500000 :credit-score 700
             :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- disburse!
  "Backend-agnostic `:loan/mark-disbursed` -- looks up the application
  via the protocol and drafts the loan-disbursement record (the
  application's OWN `:requested-amount` -- the governor has already
  verified the application's debt-to-income ratio does not exceed
  `registry/affordability-ceiling`, so this persists the amount that
  was actually approved/requested, not a substituted figure), and
  returns {:result .. :application-patch ..} for the caller to
  persist."
  [s application-id]
  (let [a (application s application-id)
        seq-n (next-sequence s (:jurisdiction a))
        result (registry/register-loan-disbursement
                application-id (:requested-amount a) (:jurisdiction a) seq-n)]
    {:result result
     :application-patch {:status :disbursed
                        :disbursement-number (get result "disbursement_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (application [_ id] (get-in @a [:applications id]))
  (all-applications [_] (sort-by :id (vals (:applications @a))))
  (creditworthiness-of [_ id] (get-in @a [:creditworthiness id]))
  (assessment-of [_ application-id] (get-in @a [:assessments application-id]))
  (ledger [_] (:ledger @a))
  (disbursement-history [_] (:disbursements @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (application-already-disbursed? [_ application-id] (= :disbursed (get-in @a [:applications application-id :status])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (swap! a update-in [:applications (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :creditworthiness/set
      (swap! a assoc-in [:creditworthiness (first path)] payload)

      :loan/mark-approved
      (swap! a assoc-in [:applications (first path) :status] :approved)

      :loan/mark-disbursed
      (let [application-id (first path)
            {:keys [result application-patch]} (disburse! s application-id)
            jurisdiction (:jurisdiction (application s application-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:applications application-id] merge application-patch)
                       (update :disbursements registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applications [s applications] (when (seq applications) (swap! a assoc :applications applications)) s))

(defn seed-db
  "A MemStore seeded with the demo application set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :creditworthiness {} :ledger [] :sequences {}
                           :disbursements []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/creditworthiness payloads, ledger
  facts, disbursement records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:application/id             {:db/unique :db.unique/identity}
   :assessment/application-id  {:db/unique :db.unique/identity}
   :creditworthiness/application-id {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :disbursement/seq           {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- application->tx [{:keys [id applicant requested-amount annual-income existing-debt
                               credit-score jurisdiction status disbursement-number]}]
  (cond-> {:application/id id}
    applicant                (assoc :application/applicant applicant)
    requested-amount          (assoc :application/requested-amount requested-amount)
    annual-income              (assoc :application/annual-income annual-income)
    existing-debt                (assoc :application/existing-debt existing-debt)
    credit-score                  (assoc :application/credit-score credit-score)
    jurisdiction                    (assoc :application/jurisdiction jurisdiction)
    status                            (assoc :application/status status)
    disbursement-number                (assoc :application/disbursement-number disbursement-number)))

(def ^:private application-pull
  [:application/id :application/applicant :application/requested-amount :application/annual-income
   :application/existing-debt :application/credit-score :application/jurisdiction
   :application/status :application/disbursement-number])

(defn- pull->application [m]
  (when (:application/id m)
    {:id (:application/id m) :applicant (:application/applicant m)
     :requested-amount (:application/requested-amount m) :annual-income (:application/annual-income m)
     :existing-debt (:application/existing-debt m) :credit-score (:application/credit-score m)
     :jurisdiction (:application/jurisdiction m) :status (:application/status m)
     :disbursement-number (:application/disbursement-number m)}))

(defrecord DatomicStore [conn]
  Store
  (application [_ id]
    (pull->application (d/pull (d/db conn) application-pull [:application/id id])))
  (all-applications [_]
    (->> (d/q '[:find [?id ...] :where [?e :application/id ?id]] (d/db conn))
         (map #(pull->application (d/pull (d/db conn) application-pull [:application/id %])))
         (sort-by :id)))
  (creditworthiness-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :creditworthiness/application-id ?aid] [?k :creditworthiness/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ application-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/application-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) application-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (disbursement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :disbursement/seq ?s] [?e :disbursement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (application-already-disbursed? [s application-id]
    (= :disbursed (:status (application s application-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (d/transact! conn [(application->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/application-id (first path) :assessment/payload (enc payload)}])

      :creditworthiness/set
      (d/transact! conn [{:creditworthiness/application-id (first path) :creditworthiness/payload (enc payload)}])

      :loan/mark-approved
      (d/transact! conn [{:application/id (first path) :application/status :approved}])

      :loan/mark-disbursed
      (let [application-id (first path)
            {:keys [result application-patch]} (disburse! s application-id)
            jurisdiction (:jurisdiction (application s application-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(application->tx (assoc application-patch :id application-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:disbursement/seq (count (disbursement-history s)) :disbursement/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-applications [s applications]
    (when (seq applications) (d/transact! conn (mapv application->tx (vals applications)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-applications s applications))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo application set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
