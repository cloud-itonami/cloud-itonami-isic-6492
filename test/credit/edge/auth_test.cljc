(ns credit.edge.auth-test
  "`credit.edge.auth`'s PORTABLE CACAO-header-verification logic,
  exercised with `mock-verifier` -- no real Ed25519/Web-Crypto signature
  crypto anywhere in this test file, by design (see `credit.edge.auth`'s
  ns docstring for the same reasoning `commitledger.edge.auth-test`
  already documents for this fleet)."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.edge.auth :as auth]))

(deftest missing-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))
        {:keys [ok? response]} (auth/verify-cacao-header v nil)]
    (is (false? ok?))
    (is (= 401 (:status response)))
    (is (= "unauthorized" (get-in response [:body :error])))))

(deftest malformed-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))
        {:keys [ok?]} (auth/verify-cacao-header v "Bearer sometoken")]
    (is (false? ok?))))

(deftest case-insensitive-cacao-scheme
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))]
    (is (true? (:ok? (auth/verify-cacao-header v "cacao abc123"))))
    (is (true? (:ok? (auth/verify-cacao-header v "CACAO abc123"))))))

(deftest invalid-signature-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? false :error "expired CACAO"}))
        {:keys [ok? response]} (auth/verify-cacao-header v "CACAO abc")]
    (is (false? ok?))
    (is (= 401 (:status response)))
    (is (= "expired CACAO" (get-in response [:body :reason])))))

(deftest valid-cacao-passes-with-iss
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))
        {:keys [ok? iss response]} (auth/verify-cacao-header v "CACAO abc")]
    (is (true? ok?))
    (is (= "did:key:zCaller" iss))
    (is (nil? response))))

(deftest always-valid-verifier-fixture
  (testing "the common positive-path test fixture"
    (let [v (auth/always-valid-verifier "did:key:zFixture")
          {:keys [ok? iss]} (auth/verify-cacao-header v "CACAO abc")]
      (is (true? ok?))
      (is (= "did:key:zFixture" iss)))))
