(ns credit.kernels.kotoba-oracle-portable-test
  "The part of the seam that has to be asked on the runtime that deploys.

  `credit.kernels.kotoba-oracle-test` is JVM-only: it compiles `.kotoba`,
  which needs the compiler. But this actor decides under `cljs.main` (the
  primary gate) and inside a Cloudflare Worker, and two ways a delegated
  seam can be green on the JVM and broken on ClojureScript were measured
  in this fleet on 2026-08-12:

  1. **`:i64` inside a record.** A top-level `:i64` argument is coerced by
     `kir/execute`, but a record FIELD goes through
     `kotoba.kir.value/bounded-typed-value!`, which on `:cljs` demands a
     `js/BigInt` and rejects a `js/Number`. `kotoba-lang/calendar`'s seam
     passed record fields unconverted and its delegated `overlaps?` had
     been throwing `value is not a signed i64` on ClojureScript for a day
     while its JVM suite stayed green. This actor's whole governor verdict
     crosses as a nine-field record, so it is exactly that shape.
  2. **`:i64` leaking back OUT.** A guest result is a `js/BigInt` on
     `:cljs`, and `(= 2 (js/BigInt 2))` is false. A host that forgot
     `i64-value` would return values that compare equal to nothing.

  Neither is visible from the JVM, so neither is asserted there. A third,
  integer-to-string formatting through `__kotoba_string_from_i64`, cannot
  arise here: the core contains no string and every export returns `:i64`.
  That is checked below too, because \"cannot arise\" is a property of
  today's core, not of the seam.

  Runs under both hosts; `credit.portable-cljs-test-runner` includes it."
  (:require [clojure.test :refer [deftest is testing]]
            [credit.kernels.gate :as gate]
            [credit.kernels.kotoba-oracle :as oracle]))

(deftest a-record-argument-crosses-on-this-runtime
  ;; The calendar failure, asked directly: build the record the way the
  ;; host builds it and hand it to the guest. On :cljs this throws
  ;; "value is not a signed i64" if `oracle/record` stopped converting.
  (testing "the whole nine-field proposal reaches the guest"
    (is (= 0 (oracle/i64-value
              (oracle/call :gate 'verdict-code
                           [(oracle/record :gate :credit.kernels/proposal
                                           {:spec-missing 0 :evidence-incomplete 0
                                            :not-approved 0 :double-disb 0
                                            :afford-applicable 0 :afford-total 0
                                            :afford-income 0 :confidence-x100 100
                                            :actuation 0})])))))
  (testing "and negative and large fields cross too"
    ;; -5 exercises the sign path; the amounts are the ones a real
    ;; application carries, in the smallest currency unit.
    (is (= 2 (gate/verdict-code 0 0 0 0 1 4301 10000 -5 0)))
    (is (= 0 (gate/verdict-code 0 0 0 0 1 4300 10000 100 0)))))

(deftest results-come-back-as-host-integers
  ;; If `i64-value` were dropped, every one of these would be a BigInt on
  ;; :cljs and `=` against a literal would be false -- so these assertions
  ;; are the check, not decoration.
  ;; Every input below is chosen to answer 1, so one uniform `=` catches a
  ;; leak from any of them.
  (doseq [[what v] [["verdict-code" (gate/verdict-code 0 0 0 0 0 0 0 59 0)]
                    ["phase-disposition" (gate/phase-disposition 1 1 0)]
                    ["phase-reason" (gate/phase-reason 0 1 0)]
                    ["norm-flag" (gate/norm-flag 7)]
                    ["confidence-low" (gate/confidence-low 59)]
                    ["affordability-exceeded" (gate/affordability-exceeded 1 4301 10000)]
                    ["op-write-enabled" (gate/op-write-enabled 3 5)]
                    ["op-auto-enabled" (gate/op-auto-enabled 3 1)]
                    ["hard-violation" (gate/hard-violation 1 0 0 0 0 0 0)]]]
    (testing what
      (is (= 1 v) (str what " did not come back as a host integer"))))
  (testing "the thresholds read at load are host integers too"
    (is (integer? gate/confidence-floor-x100))
    (is (integer? gate/affordability-ceiling-x100))
    ;; `js/Number` under :cljs; `pos-int?` is false for a BigInt there.
    (is (pos-int? gate/confidence-floor-x100))
    (is (pos-int? gate/affordability-ceiling-x100))))

(deftest the-whole-battery-runs-on-this-runtime
  ;; 52 cases, every one of them a guest call. The JVM suite asserts the
  ;; same thing; the point is that this one is asserted where the Worker
  ;; runs.
  (is (= gate/battery-case-count (gate/battery-pass-count))))

(deftest the-core-formats-no-integer-into-a-string
  ;; The third asymmetry, pinned rather than assumed: a core that renders
  ;; an integer into a string pulls in `__kotoba_string_from_i64`, which
  ;; at older kir pins guards with `(integer? start)` -- false for a
  ;; BigInt, so it works on the JVM and throws on ClojureScript. This core
  ;; has no string in it, and this is what says so if that changes.
  (let [kir (oracle/kir :gate)]
    (doseq [f (:functions kir)]
      (is (= :i64 (:result f))
          (str (:name f) " no longer returns :i64 -- if it now returns a"
               " string, check integer formatting on ClojureScript before"
               " shipping it")))))

(deftest the-host-follows-a-substituted-core-on-this-runtime-too
  ;; The delegation question, asked without a compiler: take the shipped
  ;; KIR, replace one body with a constant, register it. A host that had
  ;; kept its own copy of the phase table answers as it did before.
  (let [shipped (oracle/kir :gate)
        wrong (update shipped :functions
                      (fn [fs]
                        (mapv (fn [f]
                                (if (= 'phase-disposition (:name f))
                                  (assoc f :body 2)
                                  f))
                              fs)))]
    (is (= 0 (gate/phase-disposition 3 1 0)) "the shipped answer")
    (try
      (oracle/register-kir! :gate wrong)
      (is (= 2 (gate/phase-disposition 3 1 0))
          "phase-disposition followed the substituted core")
      (finally (oracle/deregister-kir! :gate)))
    (is (= 0 (gate/phase-disposition 3 1 0)) "restored")))
