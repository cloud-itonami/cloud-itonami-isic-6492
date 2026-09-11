(ns credit.kernels.gate-kotoba-inline-test
  "Pins the numbers the LEGACY `wasm/` ports inline against the thresholds
  the shipped decision core actually decides by.

  ## What changed under this test

  It used to compare the two `wasm/*.kotoba` ports against
  `credit.kernels.gate`'s own `defn`s, because that namespace was where the
  rules lived and the ports were replicas of it. As of 2026-08-12 it is the
  other way round: `src/credit/kernels/gate.kotoba` holds the rules,
  `credit.kernels.gate-kir` is it compiled, and `credit.kernels.gate`
  executes that. So the comparison is re-pointed at the authority, and the
  constants come from the ARTIFACT — `credit.kernels.gate/confidence-floor-
  x100` is now `(oracle/call-i64 :gate 'confidence-floor-x100 [])`, not a
  literal that a test could keep equal to another literal.

  ## What the `wasm/` ports are now

  Non-authoritative. Nothing in `src/` loads them; `credit.kernels.gate-
  kotoba` hosts them through `kototama.tender` (JVM-only Chicory) and is
  reached only from tests. They stay because they are a real second
  implementation of the same rules on a different execution path, and a
  second implementation that agrees is worth having — but they decide
  nothing, and this file no longer treats them as if they might.

  Two pins, because a threshold can go stale in two different ways:

  1. **form parity** — every function the ports share with
     `src/credit/kernels/gate.kotoba` must read as the SAME expression once
     the authority's threshold CALLS are replaced by the values the shipped
     artifact returns. This is the pin that catches a changed constant, and
     it catches a changed branch order for free.
  2. **prose** — comments that quote a threshold's value in WORDS go stale
     the same way, so they are pinned too.

  Neither writes a threshold down. Behavioural agreement between the ports
  and the authority is a separate question, answered over all 52 battery
  cases by `credit.kernels.gate-kotoba-test`.

  JVM-only (reads its sources through `java.io`), like that test —
  deliberately not part of the portable `.cljc` suite
  `credit.portable-cljs-test-runner` runs."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [credit.kernels.gate :as gate])
  (:import (java.io PushbackReader)))

;; ------------------------- sources on disk -------------------------

(def ^:private authority-path
  "The core that decides. `credit.kernels.kotoba-oracle-gen/cores` names
  the same file; it is spelled again here rather than required because
  that namespace pulls in the compiler and this test does not need it."
  "src/credit/kernels/gate.kotoba")

(def ^:private ported
  "Legacy `wasm/` port -> the authority's functions it restates. Function
  NAMES only: no threshold value appears anywhere in this test. Locking
  the set here is what keeps the pin from going quietly vacuous if a
  function is renamed or dropped (same discipline as `gate`'s own
  `battery-case-count`)."
  {"wasm/credit_verdict.kotoba"
   '#{norm-flag or2 confidence-low affordability-exceeded}
   "wasm/credit_phase.kotoba"
   '#{op-write-enabled op-auto-enabled phase-disposition phase-reason}})

(def ^:private not-form-comparable
  "Shared names deliberately left out of the form pin, and why.

  `verdict-code` and `hard-violation` take one nine-field record in the
  authority and nine (or seven) loose i32 parameters in the legacy port —
  the compiler's `max-parameters` is 5, which is why the authority packs
  them. The expressions cannot be equal, so comparing them would only be
  theatre. They are pinned BEHAVIOURALLY instead, over the whole battery,
  by `credit.kernels.gate-kotoba-test`."
  '#{verdict-code hard-violation})

(def ^:private affordability-path "wasm/affordability.kotoba")

(defn- kotoba-source [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "`.kotoba` source missing -- moved, or the test's cwd is not the repo root"
                      {:path path})))
    f))

(defn- read-forms
  "Every top-level form of `src`, READ (never evaluated). Both dialects are
  Lisp subsets the Clojure reader accepts verbatim."
  [src]
  (with-open [rdr (PushbackReader. (io/reader src))]
    (binding [*read-eval* false]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(read {:eof ::eof} rdr)))))))

(defn- legacy-defns
  "fn name -> `[params & body]` for every `defn` in the LEGACY subset,
  which carries no type annotations."
  [forms]
  (into {}
        (for [f forms :when (and (seq? f) (contains? '#{defn defn-} (first f)))]
          [(second f) (vec (drop 2 f))])))

(defn- untyped
  "A typed parameter vector with its annotations dropped: the compile
  dialect writes `[a :i64 b :i64]` where the legacy subset writes
  `[a b]`."
  [params]
  (vec (take-nth 2 params)))

(defn- authority-defns
  "fn name -> `[params & body]` for the compile-dialect core, with
  parameter types and the declared result type removed so the two dialects
  are comparable at all."
  [forms]
  (into {}
        (for [f forms :when (and (seq? f) (contains? '#{defn defn-} (first f)))]
          (let [[_ _ params & tail] f]
            ;; tail is (result-type body): one result expression per
            ;; function is a rule the compiler enforces.
            [(second f) (into [(untyped params)] (rest tail))]))))

;; ------------- the authority's thresholds, from the artifact -------

(def ^:private thresholds
  "Threshold export -> the value the SHIPPED ARTIFACT returns for it, taken
  from the loaded `credit.kernels.gate`, whose vars are oracle calls. This
  test states no number of its own; it asks the thing that decides."
  (into {} (for [[sym v] (ns-publics 'credit.kernels.gate)
                 :when (integer? @v)
                 ;; `battery-case-count` is a test-shape constant, not a
                 ;; rule, and no port inlines it.
                 :when (not= 'battery-case-count sym)]
             [sym @v])))

(defn- inline-thresholds
  "`form` with every 0-arity call to a threshold export replaced by the
  value the shipped artifact returns — i.e. the authority rewritten into
  what a port that cannot name a constant has to say."
  [form]
  (walk/postwalk (fn [x]
                   (if (and (seq? x) (= 1 (count x)) (contains? thresholds (first x)))
                     (get thresholds (first x))
                     x))
                 form))

(defn- thresholds-called [form]
  (into #{} (for [x (tree-seq coll? seq form)
                  :when (and (seq? x) (= 1 (count x)) (contains? thresholds (first x)))]
              (first x))))

;; ------------------------------ pins -------------------------------

(deftest legacy-ports-restate-the-authority-with-current-thresholds-inlined
  (let [core (authority-defns (read-forms (kotoba-source authority-path)))]
    (doseq [[path expected-fns] ported]
      (testing path
        (let [port (legacy-defns (read-forms (kotoba-source path)))
              shared (set/intersection (set (keys core)) (set (keys port)))]
          (is (= expected-fns (set/difference shared not-form-comparable))
              (str path " and " authority-path " must go on sharing exactly these"
                   " function names -- a rename on either side silently stops"
                   " pinning whatever it dropped"))
          (doseq [fn-name (sort expected-fns)]
            (testing (str fn-name)
              (is (= (inline-thresholds (get core fn-name))
                     (get port fn-name))
                  (str "`" fn-name "` has drifted between " authority-path
                       " and " path " (compared with the authority's threshold"
                       " calls replaced by what the SHIPPED artifact returns --"
                       " so a changed threshold shows up here as a changed"
                       " literal)")))))))))

(deftest every-threshold-the-ports-inline-is-pinned
  (let [core (authority-defns (read-forms (kotoba-source authority-path)))
        called (into #{} (mapcat #(thresholds-called (get core %))) (mapcat val ported))]
    (is (= '#{confidence-floor-x100 affordability-ceiling-x100} called)
        (str "the pinned functions must go on calling exactly these threshold"
             " exports; if the core stops calling one (or starts calling"
             " another), the form pin above quietly stops covering it"))))

(deftest affordability-kotoba-inlines-the-current-ceiling
  ;; `wasm/affordability.kotoba` predates the kernel and shares no function
  ;; NAME with it (`affordable?` is the inverse predicate over different
  ;; parameters), so it is pinned through the coefficient it multiplies the
  ;; income by rather than by form.
  (let [body (get (legacy-defns (read-forms (kotoba-source affordability-path)))
                  'affordable?)
        products (for [node (tree-seq coll? seq body)
                       :when (and (seq? node) (= '* (first node))
                                  (= 3 (count node))
                                  (some #{'annual-income} (rest node)))
                       :let [n (first (filter number? (rest node)))]
                       :when n]
                   n)]
    (is (some? body)
        (str affordability-path " no longer defines `affordable?` -- the only"
             " anchor this slice can be pinned through"))
    (is (= 1 (count (distinct products)))
        (str "expected exactly one income coefficient in `affordable?`, got "
             (vec products)))
    (is (= gate/affordability-ceiling-x100 (first products))
        (str affordability-path " multiplies the income by a ceiling that is no"
             " longer the one the shipped core decides by -- this slice shares"
             " no function name with the core, so it is pinned through that"
             " coefficient alone"))))

(deftest prose-quotes-of-thresholds-are-current
  (let [sources (into {} (for [path (list* authority-path affordability-path
                                            "wasm/README.md" (keys ported))]
                           [path (slurp (kotoba-source path))]))
        ;; Backticks optional: prose in `wasm/README.md` writes the name in
        ;; code spans, and a copy that markdown formats is still a copy.
        quotes (for [[path text] sources
                     [_ nm v] (re-seq #"`?([a-zA-Z][\w?*<>-]*)`?\s*=\s*(-?\d+)" text)
                     :let [sym (symbol nm)]
                     :when (contains? thresholds sym)]
                 [path sym (Long/parseLong v)])]
    (is (seq quotes)
        "no source states a threshold's value in prose any more -- if that
        is deliberate, drop this pin; otherwise the comment was lost")
    (doseq [[path sym v] quotes]
      (is (= (get thresholds sym) v)
          (str path " still says `" sym " = " v "` in prose, but the shipped"
               " core now decides by " (get thresholds sym))))
    (is (= '#{confidence-floor-x100 affordability-ceiling-x100}
           (set (for [[path sym _] quotes
                      :when (= path "wasm/credit_verdict.kotoba")]
                  sym)))
        (str "wasm/credit_verdict.kotoba's whole reason for inlining is spelled"
             " out in comments naming both thresholds -- keep them naming both"))))
