(ns wasm.credit-verdict-test
  "Hosts wasm/credit_verdict.wasm (compiled from wasm/credit_verdict.kotoba,
  see wasm/README.md) via kototama.tender -- proves credit.kernels.gate's
  governor verdict logic (`verdict-code`, `hard-violation`,
  `affordability-exceeded` and their combinators) runs as a real WASM
  guest, not just as JVM Clojure (ADR-2607141900's narrow-slice porting
  pattern, same shape as wasm.affordability-test).

  Every case below is copied verbatim from credit.kernels.gate.cljc's own
  executable `battery` (check-verdict/check-afford), so this test doubles
  as a cross-check that the .kotoba port and the in-process reference
  kernel never disagree.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the 9 real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/credit_verdict.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/credit_verdict.wasm"))))

(defn- run-verdict [spec-missing evidence-incomplete not-approved double-disb
                     afford-applicable afford-total afford-income
                     confidence-x100 actuation]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 spec-missing)
    (.writeI32 memory 4 evidence-incomplete)
    (.writeI32 memory 8 not-approved)
    (.writeI32 memory 12 double-disb)
    (.writeI32 memory 16 afford-applicable)
    (.writeI32 memory 20 afford-total)
    (.writeI32 memory 24 afford-income)
    (.writeI32 memory 28 confidence-x100)
    (.writeI32 memory 32 actuation)
    (tender/call-main instance)))

(deftest verdict-each-hard-check-dominates-alone
  (testing "no violations, high confidence, no actuation -> ok"
    (is (= 0 (run-verdict 0 0 0 0 0 0 0 100 0))))
  (testing "each hard flag alone forces hard-hold (2), confidence/actuation clean"
    (is (= 2 (run-verdict 1 0 0 0 0 0 0 100 0)) "spec-missing")
    (is (= 2 (run-verdict 0 1 0 0 0 0 0 100 0)) "evidence-incomplete")
    (is (= 2 (run-verdict 0 0 1 0 0 0 0 100 0)) "not-approved")
    (is (= 2 (run-verdict 0 0 0 1 0 0 0 100 0)) "double-disb")
    (is (= 2 (run-verdict 0 0 0 0 1 4301 10000 100 0)) "affordability exceeded")))

(deftest verdict-hard-combos-still-hard-hold
  (is (= 2 (run-verdict 1 1 1 1 1 4301 10000 100 0)))
  (is (= 2 (run-verdict 1 0 0 1 0 0 0 100 0)))
  (is (= 2 (run-verdict 0 1 1 0 0 0 0 100 0))))

(deftest verdict-confidence-floor-boundary-and-fail-closed-range
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 59 0)) "just under the floor -> escalate")
  (is (= 0 (run-verdict 0 0 0 0 0 0 0 60 0)) "at the floor -> ok")
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 0 0)) "zero confidence -> escalate")
  (is (= 0 (run-verdict 0 0 0 0 0 0 0 100 0)) "full confidence -> ok")
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 -5 0)) "negative confidence, fail-closed -> escalate")
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 150 0)) "out-of-range confidence, fail-closed -> escalate"))

(deftest verdict-actuation-always-escalates-hard-still-wins
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 100 1)))
  (is (= 2 (run-verdict 1 0 0 0 0 0 0 100 1)))
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 40 1))))

(deftest verdict-non-0-1-flags-normalize-to-violation
  (is (= 2 (run-verdict 7 0 0 0 0 0 0 100 0)) "non-0/1 spec-missing normalizes to a violation")
  (is (= 1 (run-verdict 0 0 0 0 0 0 0 100 9)) "non-0/1 actuation still escalates")
  (is (= 2 (run-verdict 0 0 0 0 5 4301 10000 100 0)) "non-0/1 afford-applicable still applies the check"))

(defn- run-afford-exceeded
  "affordability-exceeded, isolated: every other flag clean, confidence at
  the floor and actuation off, so verdict-code degenerates to exactly
  hard-violation's own affordability-exceeded call (2 iff exceeded, else 0)."
  [applicable total income]
  (= 2 (run-verdict 0 0 0 0 applicable total income 100 0)))

(deftest affordability-exact-integer-ceiling-boundary
  (is (not (run-afford-exceeded 1 4300 10000)) "43.00% exactly -> not exceeded")
  (is (run-afford-exceeded 1 4301 10000) "just over 43% -> exceeded")
  (is (not (run-afford-exceeded 1 4299 10000)) "just under 43% -> not exceeded")
  (is (not (run-afford-exceeded 1 43 100)) "43/100 exactly -> not exceeded")
  (is (run-afford-exceeded 1 44 100) "44/100 -> exceeded"))

(deftest affordability-not-applicable-and-fail-closed-ranges
  (is (not (run-afford-exceeded 0 4301 10000)) "not applicable -> never exceeded regardless of ratio")
  (is (run-afford-exceeded 1 0 0) "zero income -> fail-closed exceeded")
  (is (run-afford-exceeded 1 100 -5) "negative income -> fail-closed exceeded")
  (is (run-afford-exceeded 1 -1 100) "negative total -> fail-closed exceeded")
  (is (not (run-afford-exceeded 1 0 1)) "zero total, positive income -> not exceeded"))
