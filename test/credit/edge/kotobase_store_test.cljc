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
            [credit.edge.kotobase-http :as khttp]
            [credit.edge.kotobase-store :as ks]
            [credit.edge.kv-store :as kv]
            [credit.edge.pcompat :as pc]
            [credit.store :as store]))

(defn- nsid-from-url [url] (last (str/split url #"/xrpc/")))

(defn- fake-kotobase []
  (let [entities (atom {})
        nsid-log (atom [])]
    {:http-fn
     (khttp/resolved-mock-http-fn
      (fn [{:keys [url body]}]
        (let [nsid (nsid-from-url url)
              parsed (edn/read-string body)]
          (swap! nsid-log conj nsid)
          (case nsid
            "ai.gftd.apps.kotobase.datomic.transact"
            (let [tx (edn/read-string (:tx_edn parsed))]
              (doseq [m tx]
                (when-let [aid (:application/id m)]
                  (swap! entities assoc aid m)))
              {:status 200 :body (pr-str {:ok true})})

            "ai.gftd.apps.kotobase.datomic.q"
            (let [query (edn/read-string (:query_edn parsed))]
              (if (= (:find query) '[[?id ...]])
                {:status 200 :body (pr-str {:rows_edn (mapv (fn [id] [(pr-str id)]) (keys @entities))})}
                {:status 200 :body (pr-str {:rows_edn []})}))

            "ai.gftd.apps.kotobase.datomic.pull"
            (let [eid (:entity parsed)
                  m (get @entities eid)]
              {:status 200
               :body (pr-str {:result_edn (pr-str (into {} (map (fn [[k v]] [(pr-str k) #{(if (string? v) v (pr-str v))}])) m))})})

            {:status 404 :body (pr-str {:error (str "no fake handler for " nsid)})})))
      )
     :nsid-log nsid-log}))

(defn- counting-mint-cacao! [call-log]
  (fn [op]
    (swap! call-log conj op)
    (pc/resolved (str "fake-cacao-for-" op))))

(defn- test-remote-store []
  (let [{:keys [http-fn nsid-log]} (fake-kotobase)
        mint-log (atom [])
        s (ks/kotobase-store {:http-fn http-fn :did "did:key:z6MkFakeIsic649201"
                              :db-name "isic-6492-credit-test"
                              :mint-cacao! (counting-mint-cacao! mint-log)})]
    {:store s :nsid-log nsid-log :mint-log mint-log}))

(def ^:private demo-app
  {:id "loan-x" :applicant "Demo Applicant" :requested-amount 500000
   :annual-income 4000000 :existing-debt 500000 :credit-score 720
   :jurisdiction "JPN" :status :intake})

(deftest kotobase-store-satisfies-store-protocol-round-trip
  (let [{:keys [store]} (test-remote-store)]
    (testing "empty graph reads back nil/empty"
      (is (nil? (store/application store "nope")))
      (is (= [] (store/all-applications store))))
    (testing "write then read round-trips a real application"
      (store/with-applications store {"loan-x" demo-app})
      (is (= "Demo Applicant" (:applicant (store/application store "loan-x"))))
      (is (= 1 (count (store/all-applications store)))))))

(deftest kotobase-kv-store-satisfies-kv-store-protocol-round-trip
  (let [{:keys [store]} (test-remote-store)
        kvs (ks/kotobase-kv-store store)]
    (is (nil? (kv/kv-get-application kvs "loan-x")))
    (kv/kv-put-application! kvs "loan-x" demo-app)
    (is (= "Demo Applicant" (:applicant (kv/kv-get-application kvs "loan-x"))))
    (is (= 720 (:credit-score (kv/kv-get-application kvs "loan-x"))))))

(deftest read-cacao-minted-once-write-cacao-minted-fresh-per-transact
  (let [{:keys [store mint-log]} (test-remote-store)
        kvs (ks/kotobase-kv-store store)]
    (is (= ["datom:read"] @mint-log))
    (kv/kv-put-application! kvs "loan-x" demo-app)
    (is (= ["datom:read" "datom:transact"] @mint-log))
    (kv/kv-put-application! kvs "loan-y" (assoc demo-app :id "loan-y"))
    (is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log)
        "each write mints a FRESH CACAO -- kotobase-server's nonce-replay guard 401s a reused one")
    (kv/kv-get-application kvs "loan-x")
    (is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log) "reads reuse the single shared read CACAO")))

(deftest transact-and-q-and-pull-hit-the-real-kotobase-xrpc-nsids
  (let [{:keys [store nsid-log]} (test-remote-store)
        kvs (ks/kotobase-kv-store store)]
    (kv/kv-put-application! kvs "loan-x" demo-app)
    (kv/kv-get-application kvs "loan-x")
    (store/all-applications store)
    (is (some #{"ai.gftd.apps.kotobase.datomic.transact"} @nsid-log))
    (is (some #{"ai.gftd.apps.kotobase.datomic.pull"} @nsid-log))
    (is (some #{"ai.gftd.apps.kotobase.datomic.q"} @nsid-log))))
