(ns wasm.affordability-test
  "Hosts wasm/affordability.wasm (compiled from wasm/affordability.kotoba,
  see wasm/README.md) via kototama.tender -- proves credit.registry's
  debt-to-income affordability check runs as a real WASM guest, not just
  as JVM Clojure."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/affordability.wasm"))))

(deftest affordability-wasm-runs-with-zero-host-imports
  (is (= 1 (tender/run-main (wasm-bytes) [] (contract/host-caps {})))))
