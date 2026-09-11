(ns credit.edge.caller-allowlist-test
  "`credit.edge.caller-allowlist`'s allow-list parsing + member/non-
  member check -- the HARD, edge-layer-specific gate this repo's
  `docs/adr/0002-http-edge-loan-intake.md` adds on top of (never
  instead of) CACAO verification. Pure, no crypto involved."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.edge.caller-allowlist :as allowlist]))

(deftest parse-allowlist-splits-and-trims
  (is (= #{"did:key:zA" "did:key:zB"}
         (allowlist/parse-allowlist "did:key:zA, did:key:zB")))
  (is (= #{"did:key:zA"} (allowlist/parse-allowlist "did:key:zA")))
  (is (= #{} (allowlist/parse-allowlist "")))
  (is (= #{} (allowlist/parse-allowlist nil)))
  (is (= #{} (allowlist/parse-allowlist "  , ,")) "blank entries dropped"))

(deftest allowed-member-passes
  (let [al (allowlist/parse-allowlist "did:key:zA,did:key:zB")]
    (is (true? (allowlist/allowed? al "did:key:zA")))
    (is (true? (allowlist/allowed? al "did:key:zB")))))

(deftest non-member-is-rejected
  (let [al (allowlist/parse-allowlist "did:key:zA")]
    (is (false? (allowlist/allowed? al "did:key:zSomeoneElse")))
    (is (false? (allowlist/allowed? al nil)))))

(deftest empty-allowlist-fails-closed
  (testing "no configured caller DIDs means no caller is authorized, not 'allow everyone'"
    (is (false? (allowlist/allowed? #{} "did:key:zAnyone")))
    (is (false? (allowlist/allowed? nil "did:key:zAnyone")))))

(deftest check-shape
  (let [al (allowlist/parse-allowlist "did:key:zA")]
    (let [{:keys [ok? response]} (allowlist/check al "did:key:zA")]
      (is (true? ok?))
      (is (nil? response)))
    (let [{:keys [ok? response]} (allowlist/check al "did:key:zX")]
      (is (false? ok?))
      (is (= 403 (:status response)))
      (is (= "forbidden" (get-in response [:body :error]))))))
