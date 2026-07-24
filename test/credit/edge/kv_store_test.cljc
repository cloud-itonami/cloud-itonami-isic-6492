(ns credit.edge.kv-store-test
  "`credit.edge.kv-store`'s `MemKVStore` -- an in-memory KV stub, no real
  network/Cloudflare runtime needed. The real `CloudflareKVStore` is
  `:cljs`-only KV-binding interop, exercised at deploy time, not here --
  see `credit.edge.auth-test`'s ns docstring for the same reasoning
  applied to CACAO verify."
  (:require [clojure.test :refer [deftest is]]
            [credit.edge.kv-store :as kv]))

(def sample-app
  {:id "loan-abc123" :applicant "Test Applicant" :requested-amount 500000
   :annual-income 4000000 :existing-debt 500000 :jurisdiction "JPN"
   :status :intake :borrower-org-repo "acme/ramen-cart" :purpose "working capital"})

(deftest put-then-get-round-trips
  (let [kvs (kv/mem-kv-store)]
    (kv/kv-put-application! kvs (:id sample-app) sample-app)
    (is (= sample-app (kv/kv-get-application kvs (:id sample-app))))))

(deftest get-missing-id-is-nil
  (let [kvs (kv/mem-kv-store)]
    (is (nil? (kv/kv-get-application kvs "no-such-id")))))

(deftest put-again-overwrites
  (let [kvs (kv/mem-kv-store)]
    (kv/kv-put-application! kvs "a" sample-app)
    (kv/kv-put-application! kvs "a" (assoc sample-app :requested-amount 999))
    (is (= 999 (:requested-amount (kv/kv-get-application kvs "a"))))))

(deftest codec-round-trip-preserves-optional-fields
  (let [minimal {:id "loan-min" :status :intake}]
    (is (= minimal (kv/json->application (kv/application->json minimal))))))
