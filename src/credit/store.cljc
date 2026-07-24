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
  (:require [credit.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

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

;; ----------------------------- DatomicStore (langchain.db, or an injected db-api) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/creditworthiness payloads, ledger
  facts, disbursement records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses.

  `:ledger/seq`/`:disbursement/seq` values are ALWAYS written as STRINGS
  (`(str (count ...))`, never a raw integer) even though they are plain
  sequence counters -- kotobase-server's `do-transact` collides EVERY
  `:db.unique/identity` attribute whose value is a NUMBER (not a string)
  into entity id \"\" (empty string), silently merging every ledger/
  disbursement entry that ever hits this into ONE entity on a live
  kotobase.net graph (confirmed against production,
  ADR-2607184000's known-issues section; independently re-confirmed
  against this actor's own would-be kotobase.net graph before this
  migration -- string-valued identity attrs, e.g. `:application/id`,
  are unaffected). Applies to every backend sharing this schema/tx-
  builder code, not just the kotobase-backed one; `parse-seq-num` below
  decodes back to a number wherever numeric sort/compare is needed."
  {:application/id             {:db/unique :db.unique/identity}
   :assessment/application-id  {:db/unique :db.unique/identity}
   :creditworthiness/application-id {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :disbursement/seq           {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- parse-seq-num
  "A `:ledger/seq`/`:disbursement/seq` wire value (ALWAYS a string on
  this schema -- see schema docstring above) decoded back to a number,
  purely for local `sort-by`/comparison purposes."
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn- chain
  "`v` is either a plain value (a SYNCHRONOUS db-api, e.g. the in-process
  `langchain.db/api` default this ns's own `datomic-store` still uses
  unchanged) or, under `:cljs` when `db-api` is an INJECTED async
  backend (e.g. `langchain.kotoba-db/kotoba-api-async` pointed at a live
  kotobase.net graph -- see `credit.edge.kotobase-store`), a real
  `js/Promise`. Returns `(f v)` directly for a plain value, or a Promise
  of `(f resolved-v)` for a Promise."
  [v f]
  #?(:cljs (if (instance? js/Promise v) (.then v f) (f v))
     :clj  (f v)))

(defn- collect-chain
  "`[v1 v2 ...]` (each `chain`-able) -> a `chain`-able of a vector of
  resolved values, in order. See `commitledger.store/collect-chain`
  (same fleet, same reasoning) -- independently duplicated here rather
  than shared, per this repo's own established local-mirror-not-cross-
  repo-require convention (`credit.edge.base58`'s ns docstring)."
  [vs]
  (letfn [(go [vs acc]
            (if (empty? vs)
              acc
              (chain (first vs) (fn [v] (go (rest vs) (conj acc v))))))]
    (go vs [])))

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

(defrecord DatomicStore [conn db-api]
  Store
  ;; `application`/`all-applications`/`with-applications` are `chain`-
  ;; aware (work against EITHER a sync or an async `db-api` -- see
  ;; `chain`'s own docstring): these are the ONLY methods `credit.edge.
  ;; kotobase-store`'s edge wiring calls against a REMOTE (async)
  ;; db-api (isic-6492's ONLY exposed HTTP op, `:application/intake`,
  ;; needs no OTHER cross-application state -- see `credit.edge.loan-
  ;; endpoints`'s own ns docstring). Every other method below assumes a
  ;; SYNCHRONOUS `db-api` (true of the in-process default `datomic-
  ;; store`) -- they are only ever invoked against the in-process
  ;; snapshot store the StateGraph runs against, never against the
  ;; remote store directly (`langgraph.graph/run*` executes fully
  ;; synchronously; see `commitledger.store`'s parallel comment for the
  ;; full reasoning, identical here).
  (application [_ id]
    (chain ((:pull db-api) ((:db db-api) conn) application-pull [:application/id id])
           pull->application))
  (all-applications [_]
    (chain ((:q db-api) '[:find [?id ...] :where [?e :application/id ?id]] ((:db db-api) conn))
           (fn [ids]
             (chain (collect-chain
                     (mapv (fn [id] ((:pull db-api) ((:db db-api) conn) application-pull [:application/id id])) ids))
                    (fn [pulls] (vec (sort-by :id (map pull->application pulls))))))))
  (creditworthiness-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :creditworthiness/application-id ?aid] [?k :creditworthiness/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ application-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/application-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) application-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by (comp parse-seq-num first))
         (mapv (comp ls/dec* second))))
  (disbursement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :disbursement/seq ?s] [?e :disbursement/record ?r]] (d/db conn))
         (sort-by (comp parse-seq-num first))
         (mapv (comp ls/dec* second))))
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
      (d/transact! conn [{:assessment/application-id (first path) :assessment/payload (ls/enc payload)}])

      :creditworthiness/set
      (d/transact! conn [{:creditworthiness/application-id (first path) :creditworthiness/payload (ls/enc payload)}])

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
                      ;; :disbursement/seq is a :db.unique/identity attr --
                      ;; MUST be a string, never a raw int (see schema
                      ;; docstring: a numeric identity value collides to
                      ;; entity id "" on kotobase-server).
                      {:disbursement/seq (str (count (disbursement-history s))) :disbursement/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    ;; :ledger/seq -- same string-identity requirement, see schema docstring.
    (d/transact! conn [{:ledger/seq (str (count (ledger s))) :ledger/fact (ls/enc fact)}])
    fact)
  (with-applications [s applications]
    (if (seq applications)
      (chain ((:transact! db-api) conn (mapv application->tx (vals applications))) (fn [_] s))
      s)))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications]}]
   (let [s (->DatomicStore (d/create-conn schema) d/api)]
     (with-applications s applications))))

(defn store-with-api
  "`DatomicStore` backed by an INJECTED `db-api` map (`{:q :transact! :db
  :pull :entid}` -- `langchain.db/api`'s own shape, OR an async variant
  of it, e.g. `langchain.kotoba-db/kotoba-api-async` -- see `chain`'s
  docstring for how this record tolerates either) + a matching `conn`,
  instead of hardcoding `langchain.db`/`langchain.db/create-conn`.
  Mirrors `crm.store/store-with-api` (cloud-itonami-isic-5820,
  ADR-2607184000) / `commitledger.store/store-with-api` precisely. Does
  NOT seed demo data."
  [db-api conn]
  (->DatomicStore conn db-api))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo application set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
