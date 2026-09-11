(ns credit.edge.kv-store
  "HTTP-request-scoped persistence for isic-6492's edge layer, backed by
  a Cloudflare KV namespace -- mirrors `cloud-itonami-commitment-
  ledger`'s own `commitledger.edge.kv-store` (protocol + Mem/Cloudflare
  split, JSON-safe explicit field codec, fail-soft on read).

  DELIBERATELY SIMPLER than that mirror, in one specific way: this edge
  surface exposes ONLY `:application/intake` (create) + a by-id read
  (see this repo's own `docs/adr/0002-http-edge-loan-intake.md` -- never
  `:jurisdiction/assess`/`:creditworthiness/screen`/`:loan/approve`/
  `:loan/disburse` over HTTP, by design, to preserve isic-6492's
  independent governance). `:application/intake` is auto-eligible at
  phase 3 (`credit.phase`) and its own Governor checks
  (`credit.governor`) never inspect any OTHER application's state --
  unlike `commitledger.governor`'s check 6 (individual-lender-loan-
  count) or its double-release guard, NOTHING this edge surface runs
  needs cross-application ground truth. So there is no `ledger-state`
  companion blob and no `index` key here -- each KV entry is simply
  `application:{id}`, a single JSON-safe application map, and reads are
  always by a caller-supplied id (never a list-all). `commitledger.
  edge.kv-store`'s own `load-store`/`save-store!` full-store
  reconstruction pattern is likewise unnecessary here: `credit.edge.
  loan-endpoints/intake-core!` builds a brand-new, empty, single-
  application `credit.store/MemStore` per request (a CREATE has no
  pre-existing state to rehydrate), runs the actor graph against it,
  then persists just that one application here.

  Does NOT modify `credit.store`/`credit.governor`/`credit.phase`/
  `credit.operation` -- this is purely an additive edge-layer wrapper
  around the existing, unmodified StateGraph/Governor."
  (:require [credit.edge.pcompat :as pc]))

(defprotocol KVStore
  (kv-get-application [store id] "-> promise-like of a credit.store application map, or nil")
  (kv-put-application! [store id application] "-> promise-like of nil"))

;; ----------------------------- JSON-safe codec -----------------------------

(defn application->json
  "credit.store application map -> a plain, string-keyed, camelCase,
  JSON-safe map. Field-by-field, matching `credit.store`'s own
  `DatomicStore` `application->tx` explicit-field convention (and
  `commitledger.edge.kv-codec`'s reasoning: a generic `clj->js` walk
  would silently lose namespaced-keyword shape). Includes the two
  fields THIS integration adds beyond `credit.store`'s own demo-data
  shape (`:borrower-org-repo`/`:purpose`, carried through unvalidated --
  `credit.governor`'s checks never read them, they exist purely for
  audit/context on isic-6492's own copy of the application)."
  [{:keys [id applicant requested-amount annual-income existing-debt credit-score
           jurisdiction status disbursement-number borrower-org-repo purpose]}]
  (cond-> {}
    id (assoc "id" id)
    applicant (assoc "applicant" applicant)
    (some? requested-amount) (assoc "requestedAmount" requested-amount)
    (some? annual-income) (assoc "annualIncome" annual-income)
    (some? existing-debt) (assoc "existingDebt" existing-debt)
    (some? credit-score) (assoc "creditScore" credit-score)
    jurisdiction (assoc "jurisdiction" jurisdiction)
    status (assoc "status" (name status))
    disbursement-number (assoc "disbursementNumber" disbursement-number)
    borrower-org-repo (assoc "borrowerOrgRepo" borrower-org-repo)
    purpose (assoc "purpose" purpose)))

(defn json->application
  [m]
  (when m
    (cond-> {}
      (get m "id") (assoc :id (get m "id"))
      (get m "applicant") (assoc :applicant (get m "applicant"))
      (contains? m "requestedAmount") (assoc :requested-amount (get m "requestedAmount"))
      (contains? m "annualIncome") (assoc :annual-income (get m "annualIncome"))
      (contains? m "existingDebt") (assoc :existing-debt (get m "existingDebt"))
      (contains? m "creditScore") (assoc :credit-score (get m "creditScore"))
      (get m "jurisdiction") (assoc :jurisdiction (get m "jurisdiction"))
      (get m "status") (assoc :status (keyword (get m "status")))
      (get m "disbursementNumber") (assoc :disbursement-number (get m "disbursementNumber"))
      (get m "borrowerOrgRepo") (assoc :borrower-org-repo (get m "borrowerOrgRepo"))
      (get m "purpose") (assoc :purpose (get m "purpose")))))

;; ----------------------------- MemKVStore (tests, in-process default) -----------------------------

(defrecord MemKVStore [a] ;; a: atom of {id -> json-safe-map}
  KVStore
  (kv-get-application [_ id]
    (pc/resolved (some-> (get @a id) json->application)))
  (kv-put-application! [_ id application]
    (swap! a assoc id (application->json application))
    (pc/resolved nil)))

(defn mem-kv-store [] (->MemKVStore (atom {})))

;; ----------------------------- CloudflareKVStore (real edge runtime) -----------------------------

#?(:cljs
   (defn- app-key [id] (str "application:" id)))

#?(:cljs
   (defrecord CloudflareKVStore [env binding-name]
     KVStore
     (kv-get-application [_ id]
       (-> (.get (aget env binding-name) (app-key id))
           (.then (fn [raw] (when raw (json->application (js->clj (js/JSON.parse raw))))))))
     (kv-put-application! [_ id application]
       (.then (.put (aget env binding-name) (app-key id)
                    (js/JSON.stringify (clj->js (application->json application))))
              (fn [_] nil)))))

#?(:cljs
   (defn cloudflare-kv-store
     "The real KVStore, against the `env` binding a Cloudflare Pages
     Function's `context` carries -- `binding-name` defaults to
     `ISIC6492_LOAN_KV` (see `wrangler.jsonc`)."
     ([env] (cloudflare-kv-store env "ISIC6492_LOAN_KV"))
     ([env binding-name] (->CloudflareKVStore env binding-name))))
