(ns credit.edge.kv-store-test
  "`credit.edge.kv-store`'s `MemKVStore` -- an in-memory KV stub, no real
  network/Cloudflare runtime needed. The real `CloudflareKVStore` is
  `:cljs`-only KV-binding interop, exercised at deploy time, not here --
  see `credit.edge.auth-test`'s ns docstring for the same reasoning
  applied to CACAO verify.

  Both protocol methods return promise-like (`MemKVStore` wraps its
  results in `pcompat/resolved`), so every call is threaded through
  `await-helper` rather than read where it stands -- see that ns."
  (:require [clojure.test :refer [deftest is]]
            [credit.edge.await-helper :refer [awaiting]]
            [credit.edge.kv-store :as kv]
            [credit.edge.pcompat :as pc]))

(def sample-app
  {:id "loan-abc123" :applicant "Test Applicant" :requested-amount 500000
   :annual-income 4000000 :existing-debt 500000 :jurisdiction "JPN"
   :status :intake :borrower-org-repo "acme/ramen-cart" :purpose "working capital"})

(deftest put-then-get-round-trips
  (let [kvs (kv/mem-kv-store)]
    (awaiting (pc/then (kv/kv-put-application! kvs (:id sample-app) sample-app)
                       (fn [_] (kv/kv-get-application kvs (:id sample-app))))
              (fn [got] (is (= sample-app got))))))

(deftest get-missing-id-is-nil
  (let [kvs (kv/mem-kv-store)]
    (awaiting (kv/kv-get-application kvs "no-such-id")
              (fn [got] (is (nil? got))))))

(deftest put-again-overwrites
  (let [kvs (kv/mem-kv-store)]
    (awaiting (pc/then (kv/kv-put-application! kvs "a" sample-app)
                       (fn [_]
                         (pc/then (kv/kv-put-application! kvs "a" (assoc sample-app :requested-amount 999))
                                  (fn [_] (kv/kv-get-application kvs "a")))))
              (fn [got] (is (= 999 (:requested-amount got)))))))

(deftest codec-round-trip-preserves-optional-fields
  ;; Pure functions, no store, no promise -- this is the one test in this ns
  ;; that always ran on both runtimes.
  (let [minimal {:id "loan-min" :status :intake}]
    (is (= minimal (kv/json->application (kv/application->json minimal))))))
