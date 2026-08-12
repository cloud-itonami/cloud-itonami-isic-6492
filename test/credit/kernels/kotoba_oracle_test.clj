(ns credit.kernels.kotoba-oracle-test
  "What keeps the shipped decision core honest, now that it is what runs.

  `credit.kernels.gate-test` and `credit.governor-contract-test` state what
  the credit governor decides. That was the whole check while
  `credit.kernels.gate` held the rules itself. It is not the whole check
  any more, because those rules now live in
  `src/credit/kernels/gate.kotoba`, ship as `credit.kernels.gate-kir`, and
  are executed rather than restated. Two things have to hold that did not
  have to before:

    1. the shipped artifact IS the current source, compiled
    2. the host actually reads it, rather than having quietly kept a copy

  The second is the one that is easy to lose and impossible to see: a
  delegation that fell back to a host implementation would pass every
  behavioural test in this repository, because a host copy is exactly what
  those tests were written against. So this asks the only question that
  separates them — swap in a core that answers differently and see whether
  `credit.governor` and `credit.phase` follow.

  ## Why this file exists at all

  ADR-2608120200 measured the hole precisely: before delegation, editing a
  threshold in a `.kotoba` left all 93 tests green, because the suite
  exercised a committed `.wasm` and nothing ever recompiled the source.
  Drift on the `.kotoba` side was completely invisible. Test 1 below is
  that hole, closed, over the WHOLE artifact rather than one export —
  `kotoba-lang/com-cloudflare` found that gating one core out of nine let a
  mutation of a production file pass 94 tests.

  JVM-only: it compiles `.kotoba` and reads files. The shipped artifact it
  guards is portable and runs everywhere this actor decides."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [credit.governor :as governor]
            [credit.kernels.gate :as gate]
            [credit.kernels.kotoba-oracle :as oracle]
            [credit.kernels.kotoba-oracle-gen :as gen]
            [credit.phase :as phase]
            [credit.store :as store]))

;; ------------------------------ drift ------------------------------

(defn- renumber-gensyms
  "KIR carries compiler-generated names (`or-tmp__11099`) whose counter is
  per-JVM, so a freshly compiled artifact and a shipped one can be the
  same document and still not be `=`. Renumbering both in first-appearance
  order compares what the compiler decided rather than when it ran.

  MEASURED 2026-08-12 on compiler pin 806f5cef: this core emits ZERO such
  names, because its `.kotoba` uses no `and`/`or` special form -- the
  `or2` it calls is an ordinary function. That is a property of today's
  source, not of the format, so the normalisation stays: a future `and`
  would otherwise turn this gate from a drift check into a coin flip, and
  a coin flip that usually passes is worse than no gate."
  [kir]
  (let [seen (volatile! {})]
    (walk/postwalk
     (fn [x]
       (if (and (symbol? x) (re-find #"__\d+$" (name x)))
         (let [n (or (get @seen x)
                     (let [n (count @seen)] (vswap! seen assoc x n) n))]
           (symbol (str (str/replace (name x) #"__\d+$" "") "__" n)))
         x))
     kir)))

(deftest the-shipped-artifact-is-the-current-source-compiled
  (doseq [[id source] (sort-by key gen/cores)]
    (testing (str id " <- " source)
      (let [shipped (get oracle/shipped id)
            fresh (gen/compile-kir source)]
        (is (= (renumber-gensyms fresh) (renumber-gensyms shipped))
            (str "the shipped decision core for " id " is not " source
                 " compiled -- either the source changed without"
                 " `clojure -M:dev:test:gen`, or the artifact was edited."
                 " The deployed rules are whichever one is in the"
                 " artifact."))))))

(deftest the-whole-artifact-is-covered-not-one-export
  ;; com-cloudflare's lesson: a drift gate that names one export lets a
  ;; mutation anywhere else through. The test above compares whole KIR
  ;; documents, so this only has to keep the SET of guarded cores honest --
  ;; adding a `.kotoba` and forgetting to ship it fails here.
  (is (= (set (keys gen/cores)) (set (keys oracle/shipped)))
      "every core the generator compiles must be one the oracle ships, and
       vice versa")
  (doseq [[id _] gen/cores]
    (is (seq (:functions (oracle/kir id)))
        (str "no functions in the shipped artifact for " id))))

(deftest a-missing-core-throws-rather-than-deciding-anything
  ;; The seam's one refusal. If it fell back instead, the first thing
  ;; anyone would notice is that a decision quietly stopped being the
  ;; shipped one. Note there is nothing to fall back TO: `credit.kernels.
  ;; gate` no longer contains any of these rules.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no shipped decision core"
                        (oracle/kir :not-a-core)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare that export"
                        (oracle/param-types :gate 'no-such-export)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare that schema"
                        (oracle/schema :gate :no/such-schema)))
  (testing "a registered non-document is refused the same way as an absent one"
    (try
      (oracle/register-kir! :gate {:functions []})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no shipped decision core"
                            (oracle/kir :gate)))
      (finally (oracle/deregister-kir! :gate)))))

;; ------------------------- the ABI, from the artifact --------------

(deftest the-record-abi-is-read-out-of-the-artifact
  ;; `credit.kernels.gate` writes neither the record type nor its field
  ;; order down; it asks the shipped core and builds by name from that.
  ;; Pinned here so a rename or a reordering in `gate.kotoba` shows up as
  ;; this failing rather than as arguments that no longer mean what the
  ;; positions say.
  (is (= [[:ref :credit.kernels/proposal]] (oracle/param-types :gate 'verdict-code)))
  (is (= [[:ref :credit.kernels/proposal]] (oracle/param-types :gate 'hard-violation)))
  (is (= [:i64 :i64 :i64] (oracle/param-types :gate 'affordability-exceeded)))
  (is (= [:i64 :i64 :i64] (oracle/param-types :gate 'phase-disposition)))
  (is (= [] (oracle/param-types :gate 'confidence-floor-x100)))
  (is (= '[[:spec-missing :i64] [:evidence-incomplete :i64] [:not-approved :i64]
           [:double-disb :i64] [:afford-applicable :i64] [:afford-total :i64]
           [:afford-income :i64] [:confidence-x100 :i64] [:actuation :i64]]
         (oracle/record-fields (oracle/schema :gate :credit.kernels/proposal)))))

(deftest a-field-the-core-declares-and-the-host-omits-is-an-error
  ;; The guest takes a positional vector, so an omitted field would arrive
  ;; as whatever followed it -- or as nothing, silently. Refusing is the
  ;; only reading that is not a decision made by omission.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"no value for a field the shipped core declares"
                        (oracle/record :gate :credit.kernels/proposal
                                       {:spec-missing 0}))))

(deftest hard-violation-really-does-ignore-the-two-fields-the-host-zeroes
  ;; `credit.kernels.gate/hard-violation` passes 0 for `:confidence-x100`
  ;; and `:actuation` because the core's `hard-violation` does not read
  ;; them. That is a claim about the core, so it is asked of the core.
  (doseq [conf [-5 0 59 60 100 150]
          act [0 1 9]]
    (is (= (gate/hard-violation 0 0 0 0 1 4301 10000)
           (oracle/i64-value
            (oracle/call :gate 'hard-violation
                         [(oracle/record :gate :credit.kernels/proposal
                                         {:spec-missing 0 :evidence-incomplete 0
                                          :not-approved 0 :double-disb 0
                                          :afford-applicable 1 :afford-total 4301
                                          :afford-income 10000
                                          :confidence-x100 conf :actuation act})])))
        (str "hard-violation moved with confidence=" conf " actuation=" act))))

;; --------------------------- delegation ----------------------------

(def ^:private proposal-record
  "The record as `gate.kotoba` declares it. Spelled out HERE, unlike in
  `credit.kernels.gate`, because the substitute core below has to declare
  the same shape for the swap to be a swap and not a different module."
  (str "[:record :credit.kernels/proposal"
       " [[:spec-missing :i64] [:evidence-incomplete :i64] [:not-approved :i64]"
       "  [:double-disb :i64] [:afford-applicable :i64] [:afford-total :i64]"
       "  [:afford-income :i64] [:confidence-x100 :i64] [:actuation :i64]]]"))

(def ^:private wrong-core-source
  "Same exports, same signatures, same record: deliberately different
  answers. Every rule is inverted or flattened, so a host that had kept a
  copy of ANY of them answers as it did before and says so."
  (str "(ns credit.kernels.gate"
       "  (:schemas {:credit.kernels/proposal " proposal-record "})"
       "  (:export [confidence-floor-x100 affordability-ceiling-x100"
       "            norm-flag confidence-low affordability-exceeded hard-violation"
       "            verdict-code"
       "            op-write-enabled op-auto-enabled phase-disposition phase-reason]))"
       "(defn confidence-floor-x100 [] :i64 0)"
       "(defn affordability-ceiling-x100 [] :i64 1000)"
       ;; inverted: only exact 0 counts as YES
       "(defn norm-flag [a :i64] :i64 (if (= a 0) 1 0))"
       ;; never low, whatever the confidence
       "(defn confidence-low [x100 :i64] :i64 0)"
       ;; always exceeded, whatever the numbers
       "(defn affordability-exceeded [applicable :i64 total :i64 income :i64] :i64 1)"
       ;; never a hard violation
       "(defn hard-violation [proposal [:ref :credit.kernels/proposal]] :i64 0)"
       ;; always hard-hold
       "(defn verdict-code [proposal [:ref :credit.kernels/proposal]] :i64 2)"
       ;; every op writes at every phase, and auto-commits at none
       "(defn op-write-enabled [phase :i64 op :i64] :i64 1)"
       "(defn op-auto-enabled [phase :i64 op :i64] :i64 0)"
       ;; always hold, always phase-disabled
       "(defn phase-disposition [phase :i64 op :i64 governor-disposition :i64] :i64 2)"
       "(defn phase-reason [phase :i64 op :i64 governor-disposition :i64] :i64 1)"))

(defn- with-core
  "Run `f` against a substituted core, then put the shipped one back."
  [kir f]
  (try
    (oracle/register-kir! :gate kir)
    (f)
    (finally (oracle/deregister-kir! :gate))))

(deftest the-host-reads-the-artifact-rather-than-keeping-a-copy
  (let [wrong (gen/compile-text wrong-core-source)]
    (testing "the shipped answers"
      (is (= 0 (gate/verdict-code 0 0 0 0 0 0 0 100 0)))
      (is (= 2 (gate/verdict-code 1 0 0 0 0 0 0 100 0)))
      (is (= 1 (gate/norm-flag 7)))
      (is (= 1 (gate/confidence-low 59)))
      (is (= 0 (gate/affordability-exceeded 1 4300 10000)))
      (is (= 1 (gate/hard-violation 0 0 0 0 1 4301 10000)))
      (is (= 0 (gate/op-write-enabled 0 1)))
      (is (= 1 (gate/op-auto-enabled 3 1)))
      (is (= [0 0] [(gate/phase-disposition 3 1 0) (gate/phase-reason 3 1 0)]))
      (is (= gate/battery-case-count (gate/battery-pass-count))))
    (with-core wrong
      (fn []
        ;; A host that had kept `(if (< x100 60) 1 0)` or the phase table
        ;; would answer exactly as it did above, and nothing else in this
        ;; repository would say so.
        (is (= 2 (gate/verdict-code 0 0 0 0 0 0 0 100 0))
            "verdict-code followed the substituted core")
        (is (= 0 (gate/norm-flag 7)) "norm-flag followed it")
        (is (= 0 (gate/confidence-low 59)) "confidence-low followed it")
        (is (= 1 (gate/affordability-exceeded 1 4300 10000))
            "affordability-exceeded followed it")
        (is (= 0 (gate/hard-violation 0 0 0 0 1 4301 10000))
            "hard-violation followed it")
        (is (= 1 (gate/op-write-enabled 0 1)) "the write table followed it")
        (is (= 0 (gate/op-auto-enabled 3 1)) "the auto cell followed it")
        (is (= [2 1] [(gate/phase-disposition 3 1 0) (gate/phase-reason 3 1 0)])
            "the phase codes followed it")
        (testing "and so does everything phrased in terms of them"
          ;; The battery is 52 assertions about the shipped rules; under a
          ;; core that answers otherwise it must NOT still pass. This is
          ;; the same statement as the ones above, said at the width of
          ;; the whole executable spec.
          (is (not= gate/battery-case-count (gate/battery-pass-count))))))
    (testing "restored"
      (is (= 0 (gate/verdict-code 0 0 0 0 0 0 0 100 0)))
      (is (= gate/battery-case-count (gate/battery-pass-count))))))

(deftest the-facades-follow-the-core-too
  ;; The point of the seam is not that `credit.kernels.gate` delegates; it
  ;; is that the governor and the phase gate DECIDE by the shipped core.
  ;; So the question is asked where a caller asks it.
  (let [wrong (gen/compile-text wrong-core-source)
        st (store/seed-db)
        clean-intake {:op :application/intake :subject "app-1"}
        proposal {:confidence 1.0}]
    (testing "shipped"
      (is (true? (:ok? (governor/check clean-intake nil proposal st))))
      ;; reason 0 is `nil` at the façade, not `:none` -- `code->reason`
      ;; maps only 1 and 2.
      (is (= {:disposition :commit :reason nil}
             (phase/gate 3 {:op :application/intake} :commit)))
      (is (= {:disposition :hold :reason :phase-disabled}
             (phase/gate 1 {:op :loan/disburse} :commit))))
    (with-core wrong
      (fn []
        (is (false? (:ok? (governor/check clean-intake nil proposal st)))
            "credit.governor/check followed the substituted core")
        (is (= {:disposition :hold :reason :phase-disabled}
               (phase/gate 3 {:op :application/intake} :commit))
            "credit.phase/gate followed it where it used to commit")))
    (testing "restored"
      (is (true? (:ok? (governor/check clean-intake nil proposal st))))
      (is (= {:disposition :commit :reason nil}
             (phase/gate 3 {:op :application/intake} :commit))))))

;; ---------------------- the generator is honest --------------------

(deftest the-generator-writes-what-the-oracle-loads
  ;; If the generator wrote somewhere other than the namespace the oracle
  ;; requires, regeneration would look like it worked and change nothing.
  (doseq [[id {:keys [path]}] gen/artifact-ns]
    (is (.exists (io/file path)) (str "no artifact file for " id " at " path))
    (is (str/includes? (slurp path) "GENERATED")
        (str path " lost its generated-file header"))))
