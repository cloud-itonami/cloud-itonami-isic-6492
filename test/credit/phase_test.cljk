(ns credit.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:loan/disburse` must NEVER be a member of any phase's
  `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.phase :as phase]))

(deftest loan-disburse-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real loan disbursement"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :loan/disburse))
          (str "phase " n " must not auto-commit :loan/disburse")))))

(deftest loan-approve-never-auto-at-any-phase
  (testing "approval is a genuine underwriting decision, not just data normalization -- never auto-eligible even though it moves no capital"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :loan/approve))
          (str "phase " n " must not auto-commit :loan/approve")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":application/intake moves no capital -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:application/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :application/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :loan/disburse} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :loan/approve} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :application/intake} :commit)))))
