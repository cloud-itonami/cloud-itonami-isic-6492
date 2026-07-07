(ns credit.governor-contract-test
  "The governor contract as executable tests -- the credit-granting
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    Credit-LLM never disburses a loan the Credit Governor would
    reject, `:loan/disburse` NEVER auto-commits at any phase,
    `:application/intake` (no capital risk) MAY auto-commit when
    clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [credit.store :as store]
            [credit.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :lender :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess-app1!
  "Walks app-1 through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "app-1"} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- approve-app1!
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-approve") {:op :loan/approve :subject "app-1"} operator)
  (approve! actor (str tid-prefix "-approve")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :application/intake :subject "app-1"
                   :patch {:id "app-1" :credit-score 750}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 750 (:credit-score (store/application db "app-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "app-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "app-1")) "no assessment written"))))

(deftest affordability-exceeded-on-screen-is-held-and-unoverridable
  (testing "an application whose own debt-to-income ratio exceeds the ceiling -> HOLD on screening itself, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :creditworthiness/screen :subject "app-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:affordability-exceeded} (-> (store/ledger db) first :basis)))
      (is (nil? (store/creditworthiness-of db "app-3")) "no clearance written"))))

(deftest affordability-exceeded-on-disburse-is-held
  (testing "the SAME check applies at :loan/disburse -- recomputed straight from the application, with no dependency on whether screening ran"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :loan/disburse :subject "app-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:affordability-exceeded} (-> (store/ledger db) first :basis))))))

(deftest loan-disburse-without-approval-is-held
  (testing "a loan disbursed against a never-approved application -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :loan/disburse :subject "app-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:application-not-approved} (-> (store/ledger db) first :basis)))
      (is (empty? (store/disbursement-history db))))))

(deftest loan-disburse-without-assessment-is-held
  (testing "loan/disburse before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          _ (approve-app1! actor "t7pre")
          res (exec-op actor "t7" {:op :loan/disburse :subject "app-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) last :basis))))))

(deftest loan-disburse-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, approved, affordable loan still ALWAYS interrupts for human approval -- actuation/disburse-loan is never auto"
    (let [[db actor] (fresh)
          _ (assess-app1! actor "t8pre")
          _ (approve-app1! actor "t8pre2")
          r1 (exec-op actor "t8" {:op :loan/disburse :subject "app-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, loan-disbursement record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :disbursed (:status (store/application db "app-1"))))
          (is (= 1 (count (store/disbursement-history db))) "one draft disbursement record")))))
  (testing "reject -> hold, nothing disbursed"
    (let [[db actor] (fresh)
          _ (assess-app1! actor "t9pre")
          _ (approve-app1! actor "t9pre2")
          _ (exec-op actor "t9" {:op :loan/disburse :subject "app-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/disbursement-history db)) "nothing disbursed on reject"))))

(deftest loan-disburse-double-disbursement-is-held
  (testing "disbursing the same application twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (assess-app1! actor "t10pre")
          _ (approve-app1! actor "t10pre2")
          _ (exec-op actor "t10a" {:op :loan/disburse :subject "app-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :loan/disburse :subject "app-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-disbursement} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/disbursement-history db))) "still only the one earlier disbursement"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :application/intake :subject "app-1"
                          :patch {:id "app-1" :credit-score 750}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
