(ns credit.registry-test
  (:require [clojure.test :refer [deftest is]]
            [credit.registry :as r]))

;; ----------------------------- compute-debt-to-income-ratio -----------------------------

(deftest debt-to-income-ratio-is-existing-debt-plus-requested-over-income
  (is (= 0.25 (r/compute-debt-to-income-ratio {:existing-debt 500000 :requested-amount 500000 :annual-income 4000000})))
  (is (= 0.875 (r/compute-debt-to-income-ratio {:existing-debt 500000 :requested-amount 3000000 :annual-income 4000000}))))

(deftest debt-to-income-ratio-validation-rules
  (is (thrown? Exception (r/compute-debt-to-income-ratio {:existing-debt 0 :requested-amount 0 :annual-income 0})))
  (is (thrown? Exception (r/compute-debt-to-income-ratio {:existing-debt -1 :requested-amount 0 :annual-income 100})))
  (is (thrown? Exception (r/compute-debt-to-income-ratio {:existing-debt 0 :requested-amount -1 :annual-income 100}))))

(deftest affordability-ceiling-is-a-real-well-known-constant
  (is (= 0.43 r/affordability-ceiling)))

;; ----------------------------- register-loan-disbursement -----------------------------

(deftest loan-disbursement-is-a-draft-not-a-real-payment
  (let [result (r/register-loan-disbursement "app-1" 500000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest loan-disbursement-assigns-disbursement-number
  (let [result (r/register-loan-disbursement "app-1" 500000 "JPN" 7)]
    (is (= (get result "disbursement_number") "JPN-LOAN-000007"))
    (is (= (get-in result ["record" "application_id"]) "app-1"))
    (is (= (get-in result ["record" "disbursed_amount"]) 500000))
    (is (= (get-in result ["record" "kind"]) "loan-disbursement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest loan-disbursement-validation-rules
  (is (thrown? Exception (r/register-loan-disbursement "" 500000 "JPN" 0)))
  (is (thrown? Exception (r/register-loan-disbursement "app-1" -1 "JPN" 0)))
  (is (thrown? Exception (r/register-loan-disbursement "app-1" 500000 "" 0)))
  (is (thrown? Exception (r/register-loan-disbursement "app-1" 500000 "JPN" -1))))

(deftest disbursement-history-is-append-only
  (let [d1 (r/register-loan-disbursement "app-1" 500000 "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-loan-disbursement "app-2" 100000 "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-LOAN-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-LOAN-000001" (get-in hist2 [1 "record_id"])))))
