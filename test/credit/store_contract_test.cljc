(ns credit.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "田中 一郎" (:applicant (store/application s "app-1"))))
      (is (= "JPN" (:jurisdiction (store/application s "app-1"))))
      (is (= 500000 (:requested-amount (store/application s "app-1"))))
      (is (= ["app-1" "app-2" "app-3" "app-4"] (mapv :id (store/all-applications s))))
      (is (nil? (store/creditworthiness-of s "app-1")))
      (is (nil? (store/assessment-of s "app-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/disbursement-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/application-already-disbursed? s "app-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert
                                 :value {:id "app-1" :credit-score 750}})
        (is (= 750 (:credit-score (store/application s "app-1"))))
        (is (= "田中 一郎" (:applicant (store/application s "app-1"))) "applicant preserved"))
      (testing "assessment / creditworthiness payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["app-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "app-1")))
        (store/commit-record! s {:effect :creditworthiness/set :path ["app-1"]
                                 :payload {:application-id "app-1" :debt-to-income-ratio 0.25 :verdict :affordable}})
        (is (= :affordable (:verdict (store/creditworthiness-of s "app-1")))))
      (testing "loan approval sets status directly (no draft/certificate -- approval itself moves no capital)"
        (store/commit-record! s {:effect :loan/mark-approved :path ["app-1"]})
        (is (= :approved (:status (store/application s "app-1")))))
      (testing "loan disbursement drafts a disbursement record and advances the sequence"
        (store/commit-record! s {:effect :loan/mark-disbursed :path ["app-1"]})
        (is (= "JPN-LOAN-000000" (get (first (store/disbursement-history s)) "record_id")))
        (is (= "loan-disbursement-draft" (get (first (store/disbursement-history s)) "kind")))
        (is (= 500000 (get (first (store/disbursement-history s)) "disbursed_amount")))
        (is (= :disbursed (:status (store/application s "app-1"))))
        (is (= 1 (count (store/disbursement-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/application-already-disbursed? s "app-1")))
        (is (false? (store/application-already-disbursed? s "app-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/application s "nope")))
    (is (= [] (store/all-applications s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/disbursement-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-applications s {"x" {:id "x" :applicant "n" :requested-amount 100000
                                     :annual-income 1000000 :existing-debt 0 :credit-score 700
                                     :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:applicant (store/application s "x"))))))
