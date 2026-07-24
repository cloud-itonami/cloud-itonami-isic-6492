(ns credit.edge.loan-endpoints
  "The 2 HTTP handlers isic-6492's edge layer exposes -- `POST
  /api/loan/intake` (create) and `GET /api/loan/{id}` (read) -- mirroring
  `cloud-itonami-commitment-ledger`'s `commitledger.edge.commitment-
  endpoints` handler-boilerplate shape (a portable `*-core!` fn per
  route, `:cljs`-only `on-request-*` Cloudflare Pages Function entry
  points at the bottom that only parse `context`/produce `js/Response`).

  DELIBERATE, PERMANENT scope boundary (see `docs/adr/0002-http-edge-
  loan-intake.md`): this ns NEVER exposes `:jurisdiction/assess`/
  `:creditworthiness/screen`/`:loan/approve`/`:loan/disburse` over
  HTTP. Per `credit.phase`, only `:application/intake` is ever
  auto-eligible (phase 3's `:auto` set has exactly one member); the
  other four ops always require isic-6492's OWN human approval via its
  OWN Governor/actor graph, which this edge layer does not drive. A
  calling actor (e.g. `cloud-itonami-commitment-ledger`) may create an
  intake here; it can never push an application further than intake
  through this surface -- doing so would bypass isic-6492's independent
  governance, violating this fleet's 'no actor auto-drives another
  actor's own governed lifecycle' invariant.

  Both write and read are gated: CACAO signature+temporal verification
  (`credit.edge.auth`) THEN the caller-DID allow-list (`credit.edge.
  caller-allowlist`) -- see that ns's docstring for why both stages
  exist and why the allow-list is separate from `credit.governor`'s
  own checks. `GET /api/loan/{id}` is ALSO gated (unlike commitment-
  ledger's public GET) -- see the `get-application-core` docstring for
  why.

  Does NOT modify `credit.store`, `credit.governor`, `credit.phase`, or
  `credit.operation` -- `intake-core!` runs the EXISTING, UNMODIFIED
  `credit.operation/build` StateGraph for `:application/intake`, which
  (per `credit.phase`) auto-commits when the Governor is clean, so this
  endpoint is expected to reach `:commit` immediately -- unlike
  commitment-ledger's `:commitment/record`, which per THAT actor's own
  invariant always escalates."
  (:require [credit.edge.auth :as auth]
            [credit.edge.caller-allowlist :as allowlist]
            [credit.edge.kv-store :as kv]
            [credit.edge.pcompat :as pc]
            [credit.operation :as op]
            [credit.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- shared -----------------------------

(defn- gen-id
  []
  (str "loan-"
       #?(:cljs (.toString (js/Math.floor (* (js/Math.random) 1e12)) 36)
          :clj  (Long/toString (long (* (rand) 1e12)) 36))))

(defn- empty-application-store
  "A brand-new `credit.store/MemStore` with NO seeded applications --
  the same internal atom shape `credit.store/seed-db` starts from
  before `demo-data` is merged in, just empty. Built via the record's
  own public generated constructor (`credit.store/->MemStore`) rather
  than modifying `credit.store` to add an `empty-store` export -- this
  ns is purely an ADDITIVE consumer of the existing, unmodified `Store`
  protocol, exactly like `credit.edge.kv-store`'s own docstring
  states."
  []
  (store/->MemStore (atom {:applications {} :assessments {} :creditworthiness {}
                            :ledger [] :sequences {} :disbursements []})))

(defn- parse-intake-body
  "Raw request-body map (string/camelCase keys from the wire, OR
  already-kebab-keyword from a test fixture -- accepts either) -> a
  `credit.store` application patch. Deliberately accepts the SAME field
  names `cloud-itonami-commitment-ledger`'s `Isic6492Client` sends
  (`requestedPrincipal`/`jurisdiction`/`borrowerOrgRepo`/`purpose`) --
  see that repo's `commitledger.edge.isic6492-client/->intake-payload`
  docstring for why exactly these four fields, and why NOT
  `personalPledge` or any other commitment-ledger-specific field this
  actor has no schema for. `principal`/`requestedAmount` accepted as
  synonyms so this endpoint is usable by any caller, not only
  commitment-ledger."
  [body]
  (let [principal (or (get body :requested-principal) (get body "requestedPrincipal")
                      (get body :principal) (get body "principal")
                      (get body :requested-amount) (get body "requestedAmount"))]
    (cond-> {}
      principal (assoc :requested-amount principal)
      (or (get body :jurisdiction) (get body "jurisdiction"))
      (assoc :jurisdiction (or (get body :jurisdiction) (get body "jurisdiction")))
      (or (get body :borrower-org-repo) (get body "borrowerOrgRepo"))
      (assoc :borrower-org-repo (or (get body :borrower-org-repo) (get body "borrowerOrgRepo")))
      (or (get body :purpose) (get body "purpose"))
      (assoc :purpose (or (get body :purpose) (get body "purpose")))
      (or (get body :applicant) (get body "applicant"))
      (assoc :applicant (or (get body :applicant) (get body "applicant")))
      (or (get body :annual-income) (get body "annualIncome"))
      (assoc :annual-income (or (get body :annual-income) (get body "annualIncome")))
      (or (get body :existing-debt) (get body "existingDebt"))
      (assoc :existing-debt (or (get body :existing-debt) (get body "existingDebt"))))))

;; ----------------------------- intake -----------------------------

(defn intake-core!
  "kv-store, verifier, allowlist-set, cacao-header, body -> promise-like
  of `{:status int :body map}`. CACAO-gated THEN allow-list-gated
  (`credit.edge.auth`/`credit.edge.caller-allowlist`). Once authorized:
  builds a fresh, single-application store, runs the EXISTING,
  UNMODIFIED `credit.operation/build` StateGraph for `:application/
  intake` (auto-eligible at phase 3 -- see ns docstring), persists the
  resulting application, and returns the new application id + the
  actor's own disposition (expected `\"commit\"` on the governor-clean
  path)."
  [kv-store verifier allowlist-set cacao-header body]
  (pc/then
   (auth/verify-cacao-header verifier cacao-header)
   (fn [{:keys [ok? iss response]}]
     (if-not ok?
       (pc/resolved response)
       (let [allow-check (allowlist/check allowlist-set iss)]
         (if-not (:ok? allow-check)
           (pc/resolved (:response allow-check))
           (let [id (gen-id)
                 patch (assoc (parse-intake-body body) :id id :status :intake)
                 st (empty-application-store)
                 actor (op/build st)
                 request {:op :application/intake :subject id :patch patch}
                 context {:actor-id "isic6492-edge" :actor-role :platform-operator :phase 3}
                 result (g/run* actor {:request request :context context} {:thread-id id})
                 disposition (get-in result [:state :disposition])
                 application (store/application st id)]
             (pc/then
              (kv/kv-put-application! kv-store id application)
              (fn [_]
                (auth/json-response
                 {:ok true :id id :disposition (name (or disposition :unknown))}
                 201))))))))))

;; ----------------------------- get by id -----------------------------

(defn get-application-core
  "kv-store, allowlist-set, iss -> promise-like of `{:status int :body
  map}`, GATED (unlike commitment-ledger's public GET). isic-6492's
  application record carries real applicant/income/credit-score PII
  with no redaction convention designed for it yet (contrast
  `commitledger.edge.commitment-endpoints/public-view`, which redacts
  exactly two fields for a public matchmaking-transparency use case);
  defaulting to gated-read here is the safer choice until a redaction
  scheme is designed -- documented, not an oversight. Reuses the SAME
  allow-list as intake: only a trusted caller may read back an
  application (e.g. to confirm its own just-created intake), the exact
  shape `cloud-itonami-commitment-ledger`'s end-to-end proof uses."
  [kv-store allowlist-set iss id]
  (if-not (allowlist/allowed? allowlist-set iss)
    (pc/resolved (:response (allowlist/check allowlist-set iss)))
    (pc/then
     (kv/kv-get-application kv-store id)
     (fn [application]
       (if-not application
         (auth/json-response {:ok false :error "not found" :reason (str id " has no loan application on file")} 404)
         (auth/json-response {:ok true :application application} 200))))))

(defn get-application-core!
  "kv-store, verifier, allowlist-set, cacao-header, id -> promise-like
  of `{:status int :body map}`. CACAO-verifies THEN calls
  `get-application-core` above -- split into two fns so the CACAO-
  independent allow-list/redaction logic (`get-application-core`) stays
  trivially testable without a verifier fixture."
  [kv-store verifier allowlist-set cacao-header id]
  (pc/then
   (auth/verify-cacao-header verifier cacao-header)
   (fn [{:keys [ok? iss response]}]
     (if-not ok?
       (pc/resolved response)
       (get-application-core kv-store allowlist-set iss id)))))

;; ----------------------------- Cloudflare Pages Functions entry points (:cljs only) -----------------------------

#?(:cljs
   (defn- ->js-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn- cacao-header-of [context]
     (.get (aget (aget context "request") "headers") "authorization")))

#?(:cljs
   (defn- body-of! [context]
     (-> (.json (aget context "request"))
         (.catch (fn [_] nil)))))

#?(:cljs
   (defn- allowlist-of [env]
     (allowlist/parse-allowlist (aget env "KNOWN_CALLER_DIDS"))))

#?(:cljs
   (defn on-request-post-intake [context]
     (let [env (aget context "env")]
       (-> (body-of! context)
           (.then (fn [body]
                    (if-not body
                      (auth/json-response {:ok false :error "invalid request body"} 400)
                      (intake-core! (kv/cloudflare-kv-store env) (auth/live-verifier)
                                    (allowlist-of env) (cacao-header-of context) (js->clj body)))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-get-application [context]
     (let [env (aget context "env")
           id (aget (aget context "params") "id")]
       (-> (get-application-core! (kv/cloudflare-kv-store env) (auth/live-verifier)
                                   (allowlist-of env) (cacao-header-of context) id)
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))
