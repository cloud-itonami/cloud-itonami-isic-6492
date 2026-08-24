(ns credit.edge.auth-test
  "`credit.edge.auth`'s PORTABLE CACAO-header-verification logic,
  exercised with `mock-verifier` -- no real Ed25519/Web-Crypto signature
  crypto anywhere in this test file, by design (see `credit.edge.auth`'s
  ns docstring for the same reasoning `commitledger.edge.auth-test`
  already documents for this fleet)."
  (:require #?(:clj  [clojure.test :as t :refer [deftest is testing]]
               :cljs [cljs.test :as t :refer [deftest is testing] :include-macros true])
            [credit.edge.auth :as auth]
            [credit.edge.pcompat :as pc]))

(defn- awaiting
  "Run `f` on the value `p` resolves to, on BOTH runtimes.

  `credit.edge.*` returns promise-like, and `pcompat/resolved` is
  `#?(:cljs (js/Promise.resolve v) :clj v)`. These tests used to destructure
  the return value where it stood: on the JVM that is the value and they
  passed, on ClojureScript it is a Promise and every `:ok?` and `:status` came
  back nil. Measured 2026-08-25 before this change: 6 tests / 14 assertions /
  13 failures on nbb, 0 failures on the JVM. A `.cljc` test that only ever ran
  on one runtime (root ADR-2608730000).

  On `:clj` this is just `(f p)` -- `pcompat/then` is synchronous there, so
  nothing about the JVM run changes. On `:cljs` it returns the `async` value
  `cljs.test` looks for, so the assertions run after the promise settles."
  [p f]
  #?(:clj (f p)
     :cljs (t/async done
             (-> (js/Promise.resolve p)
                 (.then (fn [v] (f v) (done)))
                 (.catch (fn [e]
                           (is false (str "promise rejected: " e))
                           (done)))))))

(deftest missing-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))]
    (awaiting (auth/verify-cacao-header v nil)
              (fn [{:keys [ok? response]}]
                (is (false? ok?))
                (is (= 401 (:status response)))
                (is (= "unauthorized" (get-in response [:body :error])))))))

(deftest malformed-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))]
    (awaiting (auth/verify-cacao-header v "Bearer sometoken")
              (fn [{:keys [ok?]}] (is (false? ok?))))))

(deftest case-insensitive-cacao-scheme
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))]
    (awaiting (pc/then (auth/verify-cacao-header v "cacao abc123")
                       (fn [lower]
                         (pc/then (auth/verify-cacao-header v "CACAO abc123")
                                  (fn [upper] [lower upper]))))
              (fn [[lower upper]]
                (is (true? (:ok? lower)))
                (is (true? (:ok? upper)))))))

(deftest invalid-signature-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? false :error "expired CACAO"}))]
    (awaiting (auth/verify-cacao-header v "CACAO abc")
              (fn [{:keys [ok? response]}]
                (is (false? ok?))
                (is (= 401 (:status response)))
                (is (= "expired CACAO" (get-in response [:body :reason])))))))

(deftest valid-cacao-passes-with-iss
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zCaller"}))]
    (awaiting (auth/verify-cacao-header v "CACAO abc")
              (fn [{:keys [ok? iss response]}]
                (is (true? ok?))
                (is (= "did:key:zCaller" iss))
                (is (nil? response))))))

(deftest always-valid-verifier-fixture
  (let [v (auth/always-valid-verifier "did:key:zFixture")]
    (awaiting (auth/verify-cacao-header v "CACAO abc")
              (fn [{:keys [ok? iss]}]
                (testing "the common positive-path test fixture"
                  (is (true? ok?))
                  (is (= "did:key:zFixture" iss)))))))
