(ns credit.kernels.gate-kotoba-test
  "Proves credit.kernels.gate-kotoba's WASM-backed verdict-code/
  phase-disposition/phase-reason agree with credit.kernels.gate's own
  in-process functions on EVERY case in gate.cljc's own executable
  `battery` -- the same 52 cases wasm.credit-verdict-test/wasm.credit-
  phase-test already verify against the raw compiled module directly;
  this test instead calls gate-kotoba's own drop-in-compatible function
  signatures, exercising the exact call shape credit.governor/credit.phase
  would use if either were ever pointed at this namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.kernels.gate :as gate]
            [credit.kernels.gate-kotoba :as gate-kotoba]))

;; [spec evid napp dbl aapp atot ainc conf act expected]
(def verdict-cases
  [[0 0 0 0 0 0 0 100 0 0]
   [1 0 0 0 0 0 0 100 0 2]
   [0 1 0 0 0 0 0 100 0 2]
   [0 0 1 0 0 0 0 100 0 2]
   [0 0 0 1 0 0 0 100 0 2]
   [0 0 0 0 1 4301 10000 100 0 2]
   [1 1 1 1 1 4301 10000 100 0 2]
   [1 0 0 1 0 0 0 100 0 2]
   [0 1 1 0 0 0 0 100 0 2]
   [0 0 0 0 0 0 0 59 0 1]
   [0 0 0 0 0 0 0 60 0 0]
   [0 0 0 0 0 0 0 0 0 1]
   [0 0 0 0 0 0 0 100 0 0]
   [0 0 0 0 0 0 0 -5 0 1]
   [0 0 0 0 0 0 0 150 0 1]
   [0 0 0 0 0 0 0 100 1 1]
   [1 0 0 0 0 0 0 100 1 2]
   [0 0 0 0 0 0 0 40 1 1]
   [7 0 0 0 0 0 0 100 0 2]
   [0 0 0 0 0 0 0 100 9 1]
   [0 0 0 0 5 4301 10000 100 0 2]])

(deftest verdict-code-agrees-on-every-battery-case
  (doseq [[spec evid napp dbl aapp atot ainc conf act expected] verdict-cases]
    (let [in-process (gate/verdict-code spec evid napp dbl aapp atot ainc conf act)
          wasm-backed (gate-kotoba/verdict-code spec evid napp dbl aapp atot ainc conf act)]
      (is (= expected in-process wasm-backed)
          (str "inputs " [spec evid napp dbl aapp atot ainc conf act])))))

;; [phase op gov expected-disposition expected-reason]
(def phase-cases
  [[3 1 2 2 0]
   [0 0 0 0 0]
   [0 0 1 1 0]
   [1 0 1 1 0]
   [0 1 0 2 1]
   [1 2 0 2 1]
   [2 4 0 2 1]
   [2 5 0 2 1]
   [3 6 0 2 1]
   [1 1 0 1 2]
   [2 2 0 1 2]
   [2 3 0 1 2]
   [3 2 0 1 2]
   [3 3 0 1 2]
   [3 4 0 1 2]
   [3 5 0 1 2]
   [3 1 0 0 0]
   [3 1 1 1 0]
   [2 1 1 1 0]
   [-1 1 0 2 1]
   [4 1 0 2 1]])

(deftest phase-disposition-and-reason-agree-on-every-battery-case
  (doseq [[phase op gov expected-disp expected-reason] phase-cases]
    (testing (str "inputs " [phase op gov])
      (is (= expected-disp
             (gate/phase-disposition phase op gov)
             (gate-kotoba/phase-disposition phase op gov)))
      (is (= expected-reason
             (gate/phase-reason phase op gov)
             (gate-kotoba/phase-reason phase op gov))))))
