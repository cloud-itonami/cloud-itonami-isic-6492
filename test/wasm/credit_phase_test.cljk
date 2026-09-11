(ns wasm.credit-phase-test
  "Hosts wasm/credit_phase.wasm (compiled from wasm/credit_phase.kotoba,
  see wasm/README.md) via kototama.tender -- proves credit.kernels.gate's
  phase-gate logic (`phase-disposition` + `phase-reason`, packed into one
  return value since a kotoba-lang/kotoba wasm module exports exactly one
  entry point) runs as a real WASM guest, matching ADR-2607141900's
  narrow-slice porting pattern.

  Every case below is copied verbatim from credit.kernels.gate.cljc's own
  executable `battery` (check-phase).

  ABI: main is 0-arity; the 3 real i32 inputs (phase/op/governor-
  disposition) are written into the guest's exported linear memory at
  fixed offsets before calling main() -- see wasm/credit_phase.kotoba's
  ns docstring for the offset layout and the disposition/reason packing
  (10*disposition + reason)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/credit_phase.wasm"))))

(defn- run-phase [phase op governor-disposition]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 phase)
    (.writeI32 memory 4 op)
    (.writeI32 memory 8 governor-disposition)
    (let [packed (tender/call-main instance)]
      {:disposition (quot packed 10) :reason (rem packed 10)})))

(deftest phase-governor-hold-always-wins
  (is (= {:disposition 2 :reason 0} (run-phase 3 1 2))))

(deftest phase-reads-pass-through-every-disposition
  (testing "reserved op 0 always passes governor-disposition through unchanged, reason none"
    (is (= {:disposition 0 :reason 0} (run-phase 0 0 0)))
    (is (= {:disposition 1 :reason 0} (run-phase 0 0 1)))
    (is (= {:disposition 1 :reason 0} (run-phase 1 0 1)))))

(deftest phase-write-disabled-holds-with-phase-disabled-reason
  (is (= {:disposition 2 :reason 1} (run-phase 0 1 0)))
  (is (= {:disposition 2 :reason 1} (run-phase 1 2 0)))
  (is (= {:disposition 2 :reason 1} (run-phase 2 4 0)))
  (is (= {:disposition 2 :reason 1} (run-phase 2 5 0)))
  (is (= {:disposition 2 :reason 1} (run-phase 3 6 0)) "unknown op 6+ is never enabled"))

(deftest phase-enabled-but-not-auto-escalates-with-phase-approval-reason
  (is (= {:disposition 1 :reason 2} (run-phase 1 1 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 2 2 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 2 3 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 3 2 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 3 3 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 3 4 0)))
  (is (= {:disposition 1 :reason 2} (run-phase 3 5 0))))

(deftest phase-the-single-auto-cell
  (testing "phase 3 x application/intake (op 1) is the only auto-commit cell"
    (is (= {:disposition 0 :reason 0} (run-phase 3 1 0)))))

(deftest phase-governor-escalate-passes-through-an-enabled-write
  (is (= {:disposition 1 :reason 0} (run-phase 3 1 1)))
  (is (= {:disposition 1 :reason 0} (run-phase 2 1 1))))

(deftest phase-out-of-range-phases-have-no-writes
  (is (= {:disposition 2 :reason 1} (run-phase -1 1 0)))
  (is (= {:disposition 2 :reason 1} (run-phase 4 1 0))))
