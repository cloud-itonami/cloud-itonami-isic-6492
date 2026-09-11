(ns credit.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. unknown, all governor dispositions). The façade
     delegates, so this is the guard that delegation didn't change
     semantics.
  3. governor boundary — the confidence floor boundary, the
     fail-closed treatment of out-of-range confidence, and the
     debt-to-income affordability ceiling boundary, exercised through
     the real `credit.governor/check` façade."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.governor :as governor]
            [credit.kernels.gate :as gate]
            [credit.registry :as registry]
            [credit.store :as store]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

(deftest affordability-ceiling-pinned-to-facade-constant
  (is (= gate/affordability-ceiling-x100
         (Math/round (* 100.0 registry/affordability-ceiling)))
      "the registry's documented 0.43 and the kernel's deciding 43 must not drift"))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. Op 0 is
;; the fleet-wide read code (this actor's read-ops is empty; the
;; façade never emits it) and op 6 the unknown-write code.

(def ^:private ref-read-ops #{0})
(def ^:private ref-phases
  {0 {:writes #{}            :auto #{}}
   1 {:writes #{1}           :auto #{}}
   2 {:writes #{1 2 3}       :auto #{}}
   3 {:writes #{1 2 3 4 5}   :auto #{1}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (189 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5 6]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest loan-disburse-and-approve-auto-enabled-nowhere
  (testing "op 5 (:loan/disburse) and op 4 (:loan/approve) are auto-enabled at
            NO phase — kernel restates the phase table's permanent structural
            invariants"
    (doseq [phase [-1 0 1 2 3 4 7]]
      (is (= 0 (gate/op-auto-enabled phase 5)))
      (is (= 0 (gate/op-auto-enabled phase 4))))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :application/intake
;; touches neither the store nor the evidence/approval/affordability
;; checks, so the verdict is decided purely by confidence/actuation —
;; nil store is safe.

(defn- verdict [proposal]
  (governor/check {:op :application/intake :subject "app-x"} {} proposal nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

;; ---------------------------------------------------------------
;; Affordability ceiling boundary through the real façade — the
;; kernel decides in exact integers (100*total > 43*income), the
;; façade still produces the human-readable violation map, and both
;; must agree at the boundary.

(defn- store-with-dti
  "A MemStore holding one application with the given raw affordability
  fields (total debt-to-income ratio = (existing + requested)/income)."
  [existing-debt requested-amount annual-income]
  (store/with-applications
    (store/seed-db)
    {"app-x" {:id "app-x" :applicant "n" :requested-amount requested-amount
              :annual-income annual-income :existing-debt existing-debt
              :credit-score 700 :jurisdiction "JPN" :status :intake}}))

(defn- screen-verdict [existing requested income]
  (governor/check {:op :creditworthiness/screen :subject "app-x"} {}
                  {:confidence 0.9}
                  (store-with-dti existing requested income)))

(deftest affordability-ceiling-boundary-through-facade
  (testing "dti exactly at the 0.43 ceiling clears (strict >, not >=)"
    (let [v (screen-verdict 2150000 2150000 10000000)]
      (is (true? (:ok? v)))
      (is (false? (:hard? v)))
      (is (empty? (:violations v)))))
  (testing "one currency unit over the ceiling hard-holds, kernel and
            violation map agreeing"
    (let [v (screen-verdict 2150000 2150001 10000000)]
      (is (true? (:hard? v)))
      (is (false? (:ok? v)))
      (is (some #{:affordability-exceeded} (mapv :rule (:violations v))))))
  (testing "one currency unit under the ceiling clears"
    (is (true? (:ok? (screen-verdict 2150000 2149999 10000000))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/disburse-loan}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :jurisdiction/assess :subject "app-x"} {}
                            {:confidence 0.99 :stake :actuation/disburse-loan :cites []} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:no-spec-basis} (mapv :rule (:violations v)))))))
