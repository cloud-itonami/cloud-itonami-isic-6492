(ns credit.edge.kotobase-store-test
  "Mock-based proof that `credit.edge.kotobase-store`'s injected
  `:db-api` genuinely satisfies the `{:q :transact! :db :pull :entid}`
  shape `langchain.db`/`langchain.kotoba-db` expect, AND that
  `KotobaseKVStore` (the `credit.edge.kv-store/KVStore` protocol
  implementation this ns adds) round-trips correctly against a fake
  kotobase.net. Direct mirror of `commitledger.edge.kotobase-store-
  test` (sibling actor, same fleet) -- see that ns's docstring for the
  full platform-split reasoning (`:clj`-only, `resolved-mock-http-fn`,
  `mint-cacao!` here a plain counting stub not real CACAO crypto)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [credit.edge.await-helper :refer [awaiting sequentially]]
            [credit.edge.kotobase-http :as khttp]
            [credit.edge.kotobase-store :as ks]
            [credit.edge.kv-store :as kv]
            [credit.edge.pcompat :as pc]
            [credit.store :as store]))

(defn- nsid-from-url [url] (last (str/split url #"/xrpc/")))

;; The fake kotobase below has to speak the SAME wire format the store speaks,
;; and that format is platform-split: `credit.edge.kotobase-store`'s private
;; `json-write`/`json-read` are `pr-str`/`edn/read-string` on the JVM and
;; `js/JSON.stringify`/`js/JSON.parse` on ClojureScript. The original fake
;; used `edn/read-string` and `pr-str` unconditionally, so on ClojureScript it
;; was handed JSON and asked to read it as EDN. These two mirror the store.
;;
;; The INNER `pr-str`/`edn/read-string` calls below are a different thing and
;; stay as they are: `tx_edn`, `rows_edn` and `result_edn` are EDN-in-a-string
;; fields by the XRPC schema's own naming, on both platforms.
(defn- read-body [body]
  #?(:cljs (js->clj (js/JSON.parse body) :keywordize-keys true)
     :clj  (edn/read-string body)))

(defn- write-body [m]
  #?(:cljs (js/JSON.stringify (clj->js m))
     :clj  (pr-str m)))

(defn- fake-kotobase []
  (let [entities (atom {})
        nsid-log (atom [])]
    {:http-fn
     (khttp/resolved-mock-http-fn
      (fn [{:keys [url body]}]
        (let [nsid (nsid-from-url url)
              parsed (read-body body)]
          (swap! nsid-log conj nsid)
          (case nsid
            "ai.gftd.apps.kotobase.datomic.transact"
            (let [tx (edn/read-string (:tx_edn parsed))]
              (doseq [m tx]
                (when-let [aid (:application/id m)]
                  (swap! entities assoc aid m)))
              {:status 200 :body (write-body {:ok true})})

            "ai.gftd.apps.kotobase.datomic.q"
            (let [query (edn/read-string (:query_edn parsed))]
              (if (= (:find query) '[[?id ...]])
                {:status 200 :body (write-body {:rows_edn (mapv (fn [id] [(pr-str id)]) (keys @entities))})}
                {:status 200 :body (pr-str {:rows_edn []})}))

            "ai.gftd.apps.kotobase.datomic.pull"
            (let [eid (:entity parsed)
                  m (get @entities eid)]
              {:status 200
               :body (write-body {:result_edn (pr-str (into {} (map (fn [[k v]] [(pr-str k) #{(if (string? v) v (pr-str v))}])) m))})})

            {:status 404 :body (write-body {:error (str "no fake handler for " nsid)})})))
      )
     :nsid-log nsid-log}))

(defn- counting-mint-cacao! [call-log]
  (fn [op]
    (swap! call-log conj op)
    (pc/resolved (str "fake-cacao-for-" op))))

(defn- test-remote-store
  "Promise-like of `{:store :nsid-log :mint-log}`.

  `ks/kotobase-store` says in its own docstring that it `Returns a
  promise-like of a ready DatomicStore` -- it mints the read CACAO before
  it can hand one back. On the JVM `pcompat/then` is direct so the store
  comes back as itself; on ClojureScript it is a Promise. This fn used to
  put whatever came back straight into the map, which is why every test
  here reported `No protocol method Store.application defined for type
  object: [object Promise]` on ClojureScript."
  []
  (let [{:keys [http-fn nsid-log]} (fake-kotobase)
        mint-log (atom [])]
    (pc/then (ks/kotobase-store {:http-fn http-fn :did "did:key:z6MkFakeIsic649201"
                                 :db-name "isic-6492-credit-test"
                                 :mint-cacao! (counting-mint-cacao! mint-log)})
             (fn [s] {:store s :nsid-log nsid-log :mint-log mint-log}))))

(def ^:private demo-app
  {:id "loan-x" :applicant "Demo Applicant" :requested-amount 500000
   :annual-income 4000000 :existing-debt 500000 :credit-score 720
   :jurisdiction "JPN" :status :intake})

(deftest kotobase-store-satisfies-store-protocol-round-trip
  (awaiting
    (pc/then (test-remote-store)
      (fn [{:keys [store]}]
        (sequentially
          [#(pc/then (store/application store "nope")
                     (fn [v] (testing "empty graph reads back nil"
                               (is (nil? v)))))
           #(pc/then (store/all-applications store)
                     (fn [v] (testing "empty graph reads back empty"
                               (is (= [] v)))))
           #(store/with-applications store {"loan-x" demo-app})
           #(pc/then (store/application store "loan-x")
                     (fn [v] (testing "write then read round-trips a real application"
                               (is (= "Demo Applicant" (:applicant v))))))
           #(pc/then (store/all-applications store)
                     (fn [v] (is (= 1 (count v)))))])))
    (fn [_] nil)))

(deftest kotobase-kv-store-satisfies-kv-store-protocol-round-trip
  (awaiting
    (pc/then (test-remote-store)
      (fn [{:keys [store]}]
        (let [kvs (ks/kotobase-kv-store store)]
          (sequentially
            [#(pc/then (kv/kv-get-application kvs "loan-x") (fn [v] (is (nil? v))))
             #(kv/kv-put-application! kvs "loan-x" demo-app)
             #(pc/then (kv/kv-get-application kvs "loan-x")
                       (fn [v] (is (= "Demo Applicant" (:applicant v)))))
             #(pc/then (kv/kv-get-application kvs "loan-x")
                       (fn [v] (is (= 720 (:credit-score v)))))]))))
    (fn [_] nil)))

(deftest read-cacao-minted-once-write-cacao-minted-fresh-per-transact
  ;; Every assertion here reads an ATOM a preceding call appends to, so each
  ;; one gets its own step: on the JVM the append has happened by the time the
  ;; call returns, and on ClojureScript it has not.
  (awaiting
    (pc/then (test-remote-store)
      (fn [{:keys [store mint-log]}]
        (let [kvs (ks/kotobase-kv-store store)]
          (is (= ["datom:read"] @mint-log)
              "constructing the store mints exactly one read CACAO")
          (sequentially
            [#(kv/kv-put-application! kvs "loan-x" demo-app)
             #(is (= ["datom:read" "datom:transact"] @mint-log))
             #(kv/kv-put-application! kvs "loan-y" (assoc demo-app :id "loan-y"))
             #(is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log)
                  "each write mints a FRESH CACAO -- kotobase-server's nonce-replay guard 401s a reused one")
             #(kv/kv-get-application kvs "loan-x")
             #(is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log)
                  "reads reuse the single shared read CACAO")]))))
    (fn [_] nil)))

(deftest transact-and-q-and-pull-hit-the-real-kotobase-xrpc-nsids
  (awaiting
    (pc/then (test-remote-store)
      (fn [{:keys [store nsid-log]}]
        (let [kvs (ks/kotobase-kv-store store)]
          (sequentially
            [#(kv/kv-put-application! kvs "loan-x" demo-app)
             #(kv/kv-get-application kvs "loan-x")
             #(store/all-applications store)
             #(do (is (some #{"ai.gftd.apps.kotobase.datomic.transact"} @nsid-log))
                  (is (some #{"ai.gftd.apps.kotobase.datomic.pull"} @nsid-log))
                  (is (some #{"ai.gftd.apps.kotobase.datomic.q"} @nsid-log)))]))))
    (fn [_] nil)))
