(ns credit.edge.kotobase-store
  "The injected-`:db-api` `credit.store/DatomicStore` constructor pointed
  at a live kotobase-server graph (kotobase.net), self-sovereign via
  this actor's own Ed25519 key -- kotobase-persistence-migration,
  docs/adr/0003-kotobase-persistence-migration.md. Direct mirror of
  `commitledger.edge.kotobase-store` (sibling actor, same fleet) --
  see that ns's docstring for the full architectural reasoning
  (`langchain.kotoba-db/kotoba-api-async` instead of the original
  synchronous `kotoba-api`, since this is a real Cloudflare Pages
  Function with no synchronous I/O primitive at all).

  Rather than replacing `credit.edge.kv-store`'s role with a new,
  differently-shaped boundary, this ns instead implements `credit.edge.
  kv-store`'s OWN `KVStore` protocol (`KotobaseKVStore` below, 2
  methods: `kv-get-application`/`kv-put-application!` -- SIMPLER than
  commitment-ledger's counterpart, mirroring `credit.store`'s own
  smaller surface + `credit.edge.loan-endpoints`'s own scope boundary,
  ns docstring: this edge surface exposes ONLY `:application/intake`,
  which touches no OTHER application's state, so there is no full-store
  hydrate/persist boundary the way commitment-ledger's cross-
  application Governor checks need). This means `credit.edge.loan-
  endpoints/intake-core!`/`get-application-core` -- and EVERY existing
  test in `loan_endpoints_test.cljc` that drives them via `kv/mem-kv-
  store` -- need ZERO changes: only the bottom-of-file `on-request-*`
  entry points swap `(kv/cloudflare-kv-store env)` for `(kotobase-kv-
  store-from-env! env)`."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [credit.edge.kotobase-http :as khttp]
            [credit.edge.kotobase-identity :as identity]
            [credit.edge.kv-store :as kv]
            [credit.edge.pcompat :as pc]
            [credit.store :as store]
            [langchain.kotoba-db :as kdb]))

(defn- json-write
  [m]
  #?(:cljs (js/JSON.stringify (clj->js m))
     :clj  (pr-str m)))

(defn- json-read
  [s]
  #?(:cljs (js->clj (js/JSON.parse s) :keywordize-keys true)
     :clj  (edn/read-string s)))

(defn db-api-for [http-fn]
  (kdb/kotoba-api-async {:http-fn http-fn :json-write json-write :json-read json-read}))

(defn kotobase-store
  "A `credit.store/Store` (via `credit.store/store-with-api`) backed by
  a live kotobase-server graph, self-sovereign via this actor's own
  Ed25519 key. Returns a promise-like of a ready `DatomicStore`.

  opts: `:http-fn` (REQUIRED), `:did`, `:db-name` (default `kotobase-
  identity/default-db-name` -- used for BOTH reads and writes, no
  precomputed graph CID needed at all, see `commitledger.edge.
  kotobase-store/kotobase-store`'s own docstring for the confirmed-live
  finding this mirrors), `:url` (default \"https://kotobase.net\"),
  `:mint-cacao!` (REQUIRED -- `(fn [op] => promise-like CACAO)`,
  `op` \"datom:read\"/\"datom:transact\")."
  [{:keys [http-fn url db-name mint-cacao! did]
    :or {url "https://kotobase.net" db-name identity/default-db-name}}]
  (let [api (db-api-for http-fn)]
    (pc/then
     (mint-cacao! "datom:read")
     (fn [read-cacao]
       (let [read-conn (kdb/kotoba-conn* url db-name {:cacao read-cacao :did did})
             write-conn! (fn []
                           (pc/then (mint-cacao! "datom:transact")
                                    (fn [write-cacao]
                                      (kdb/kotoba-conn* url db-name {:cacao write-cacao :did did}))))
             remote-api {:transact! (fn [_conn tx-data]
                                      (pc/then (write-conn!) (fn [wc] ((:transact! api) wc tx-data))))
                         :db identity
                         :q (fn [query _conn & inputs] (apply (:q api) query read-conn inputs))
                         :pull (fn [_conn pattern eid] ((:pull api) read-conn pattern eid))
                         :entid (fn [_conn eid] ((:entid api) read-conn eid))}]
         (store/store-with-api remote-api read-conn))))))

;; ───────── KVStore protocol impl (replaces CloudflareKVStore in PRODUCTION) ─

(defrecord KotobaseKVStore [remote-store]
  kv/KVStore
  (kv-get-application [_ id] (store/application remote-store id))
  (kv-put-application! [_ id application]
    (pc/then (store/with-applications remote-store {id application}) (fn [_] nil))))

(defn kotobase-kv-store
  "A `credit.edge.kv-store/KVStore` backed by `remote-store` (a
  `kotobase-store` result)."
  [remote-store]
  (->KotobaseKVStore remote-store))

;; ───────── production wiring (:cljs only -- real identity + real fetch) ────

#?(:cljs
   (defn kotobase-kv-store-from-env!
     "env -> promise-like of a ready `KVStore` backed by kotobase.net,
     using this actor's own self-mint identity ($ISIC6492_ACTOR_SEED/
     _DID) + real `js/fetch`. Drop-in replacement for `credit.edge.kv-
     store/cloudflare-kv-store` at every `on-request-*` call site.
     FAIL-CLOSED: rejects if the identity is unconfigured or CACAO
     minting fails -- never silently falls back to KV (this migration's
     explicit requirement)."
     [env]
     (pc/then
      (identity/signing-key-from-env env)
      (fn [signing-key]
        (if-not signing-key
          (js/Promise.reject
           (js/Error. "credit.edge.kotobase-store: no self-mint identity -- $ISIC6492_ACTOR_SEED/$ISIC6492_ACTOR_DID unset"))
          (pc/then
           (kotobase-store
            {:http-fn khttp/fetch-http-fn
             :did (:did signing-key)
             :db-name identity/default-db-name
             :mint-cacao! (fn [op] (identity/mint-kotobase-cacao! signing-key op))})
           kotobase-kv-store))))))
