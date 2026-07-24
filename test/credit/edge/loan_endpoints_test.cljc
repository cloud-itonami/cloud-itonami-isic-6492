(ns credit.edge.loan-endpoints-test
  "The 2 edge handlers' CORE request/response shape (`intake-core!`/
  `get-application-core!`), using `credit.edge.auth/mock-verifier` +
  `credit.edge.kv-store/mem-kv-store` -- no real network/Cloudflare
  runtime, no real crypto. Runs `credit.operation/build`'s EXISTING,
  UNMODIFIED StateGraph for real (not mocked) -- this is what actually
  proves `:application/intake` reaches `:commit` immediately through
  this HTTP surface, per `docs/adr/0002-http-edge-loan-intake.md`."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.edge.auth :as auth]
            [credit.edge.caller-allowlist :as allowlist]
            [credit.edge.kv-store :as kv]
            [credit.edge.loan-endpoints :as ep]))

(def caller-did "did:key:zCommitmentLedgerActor01")
(def allow (allowlist/parse-allowlist caller-did))

(def intake-body
  {:requested-principal 300000 :jurisdiction "JPN"
   :borrower-org-repo "acme/ramen-cart" :purpose "working capital"})

;; ----------------------------- intake -----------------------------

(deftest intake-requires-cacao
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier caller-did)
        {:keys [status]} (ep/intake-core! kvs v allow nil intake-body)]
    (is (= 401 status))))

(deftest intake-requires-allowlist-membership
  (testing "a validly-signed CACAO from an UNLISTED caller is still forbidden"
    (let [kvs (kv/mem-kv-store)
          v (auth/always-valid-verifier "did:key:zSomeRandomActor")
          {:keys [status]} (ep/intake-core! kvs v allow "CACAO abc" intake-body)]
      (is (= 403 status))
      (is (empty? (kv/kv-get-application kvs "anything")) "nothing was written"))))

(deftest intake-happy-path-reaches-commit-immediately
  (testing ":application/intake is auto-eligible at phase 3 (credit.phase) -- unlike
            commitment-ledger's :commitment/record, this reaches :commit synchronously,
            through the SAME unmodified credit.operation StateGraph"
    (let [kvs (kv/mem-kv-store)
          v (auth/always-valid-verifier caller-did)
          {:keys [status body]} (ep/intake-core! kvs v allow "CACAO abc" intake-body)]
      (is (= 201 status))
      (is (true? (:ok body)))
      (is (string? (:id body)))
      (is (= "commit" (:disposition body)))
      (let [stored (kv/kv-get-application kvs (:id body))]
        (is (= 300000 (:requested-amount stored)))
        (is (= "JPN" (:jurisdiction stored)))
        (is (= "acme/ramen-cart" (:borrower-org-repo stored)))
        (is (= "working capital" (:purpose stored)))
        (is (= :intake (:status stored)))))))

(deftest intake-accepts-principal-synonym
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier caller-did)
        {:keys [body]} (ep/intake-core! kvs v allow "CACAO abc" {:principal 150000 :jurisdiction "USA"})]
    (is (= 150000 (:requested-amount (kv/kv-get-application kvs (:id body)))))))

;; ----------------------------- get-application -----------------------------

(deftest get-application-requires-cacao
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier caller-did)
        {:keys [status]} (ep/get-application-core! kvs v allow nil "loan-x")]
    (is (= 401 status))))

(deftest get-application-requires-allowlist-membership
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier "did:key:zUnlisted")
        {:keys [status]} (ep/get-application-core! kvs v allow "CACAO abc" "loan-x")]
    (is (= 403 status))))

(deftest get-application-unknown-id-is-404
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier caller-did)
        {:keys [status]} (ep/get-application-core! kvs v allow "CACAO abc" "no-such-id")]
    (is (= 404 status))))

(deftest get-application-returns-what-was-created
  (let [kvs (kv/mem-kv-store)
        v (auth/always-valid-verifier caller-did)
        {:keys [body]} (ep/intake-core! kvs v allow "CACAO abc" intake-body)
        id (:id body)
        {:keys [status body]} (ep/get-application-core! kvs v allow "CACAO abc" id)]
    (is (= 200 status))
    (is (true? (:ok body)))
    (is (= id (get-in body [:application :id])))
    (is (= 300000 (get-in body [:application :requested-amount])))))
