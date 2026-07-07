(ns wasm.affordability-test
  "Hosts wasm/affordability.wasm (compiled from wasm/affordability.kotoba,
  see wasm/README.md) via kototama.tender -- proves credit.registry's
  debt-to-income affordability check runs as a real WASM guest, not just
  as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the three real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/affordability.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/affordability.wasm"))))

(defn- run-affordable? [existing-debt requested-amount annual-income]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 existing-debt)
    (.writeI32 memory 4 requested-amount)
    (.writeI32 memory 8 annual-income)
    (tender/call-main instance)))

(deftest affordability-wasm-approves-within-ceiling
  (testing "43% back-end DTI ceiling not exceeded -> affordable"
    (is (= 1 (run-affordable? 500000 2000000 6000000)))))

(deftest affordability-wasm-rejects-over-ceiling
  (testing "43% back-end DTI ceiling exceeded -> not affordable"
    (is (= 0 (run-affordable? 3000000 3000000 6000000)))))

(deftest affordability-wasm-rejects-zero-income
  (testing "non-positive annual income -> not affordable (not a crash)"
    (is (= 0 (run-affordable? 0 0 0)))))
