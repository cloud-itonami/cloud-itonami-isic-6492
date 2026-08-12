(ns credit.kernels.gate-kotoba-inline-test
  "Pins the numbers the `.kotoba` ports INLINE against
  `credit.kernels.gate`'s named constants.

  `credit.kernels.gate` NAMES its thresholds (`confidence-floor-x100`,
  `affordability-ceiling-x100`). The `.kotoba` subset has no top-level
  `def` -- `wasm/credit_verdict.kotoba` says so itself -- so each port
  carries the VALUE written straight into the expression. Nothing made
  the two agree: editing `(def confidence-floor-x100 60)` left the
  compiled credit-decision gate (`credit.kernels.gate-kotoba`, and the
  wasm the edge serves) deciding on the OLD number, with no diff, no
  failing test and no warning. `credit.kernels.gate-test` already pins
  the two façade copies (`credit.governor/confidence-floor`,
  `credit.registry/affordability-ceiling`); these are the same pins for
  the copies that live on the far side of the emit boundary.

  Both sides are read at test time -- the constants from the loaded
  `credit.kernels.gate` vars, the literals from the `.kotoba` sources on
  disk. NO threshold is written down in this file: a test that restated
  the number would only add a fourth copy of the same defect.

  Three pins, one per way a gate constant is duplicated today:

  1. port parity -- every function the two `.kotoba` gate ports share
     with `credit.kernels.gate` must read as the SAME form once gate's
     named constants are substituted for their current values. This is
     the pin that catches the constant drift, and it catches any other
     divergence in the ported branch order for free -- both ports call
     themselves verbatim copies in their own ns comments.
  2. affordability slice -- `wasm/affordability.kotoba` predates the
     kernel and shares no function NAME with it (`affordable?` is the
     inverse predicate over different parameters), so it is pinned
     through the coefficient it multiplies the income by.
  3. prose -- the comments and docstring that quote a constant's value
     in WORDS go stale exactly the same way, so they are pinned too.

  JVM-only (reads its sources through `java.io`), like
  `credit.kernels.gate-kotoba-test` -- deliberately not part of the
  portable `.cljc` suite `credit.portable-cljs-test-runner` runs."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [credit.kernels.gate :as gate])
  (:import (java.io PushbackReader)))

;; ------------------------- sources on disk -------------------------

(def ^:private gate-path
  "`credit.kernels.gate`'s own source, off the classpath (`:paths [\"src\"]`)."
  "credit/kernels/gate.cljc")

(def ^:private ported
  "`.kotoba` port -> the `credit.kernels.gate` functions it restates.
  Function NAMES only: no threshold value appears anywhere in this test.
  Locking the set here is what keeps the pin from going quietly vacuous
  if a port is renamed or dropped (same discipline as `gate`'s own
  `battery-case-count`)."
  {"wasm/credit_verdict.kotoba"
   '#{not-flag norm-flag and2 or2 or3 or5
      confidence-low affordability-exceeded hard-violation verdict-code}
   "wasm/credit_phase.kotoba"
   '#{op-write-enabled op-auto-enabled phase-disposition phase-reason}})

(def ^:private affordability-path "wasm/affordability.kotoba")

(defn- gate-source []
  (or (io/resource gate-path)
      (throw (ex-info "credit.kernels.gate source not on the classpath"
                      {:path gate-path}))))

(defn- kotoba-source [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "`.kotoba` port missing -- moved, or the test's cwd is not the repo root"
                      {:path path})))
    f))

(defn- read-forms
  "Every top-level form of `src`, READ (never evaluated). Both sides are
  plain data: `gate.cljc` carries no reader conditional, and `.kotoba`
  is a Lisp subset the Clojure reader accepts verbatim."
  [src]
  (with-open [rdr (PushbackReader. (io/reader src))]
    (binding [*read-eval* false]
      (doall (take-while #(not= ::eof %)
                         (repeatedly #(read {:read-cond :allow
                                             :features #{:clj}
                                             :eof ::eof}
                                            rdr)))))))

(defn- defns
  "fn name -> `[params & body]` for every `defn`/`defn-` in `forms`, with
  docstring and attr-map stripped (the ports carry no docstrings)."
  [forms]
  (into {}
        (for [f forms :when (and (seq? f) (contains? '#{defn defn-} (first f)))]
          (let [tail (drop 2 f)
                tail (cond-> tail (string? (first tail)) rest)
                tail (cond-> tail (map? (first tail)) rest)]
            [(second f) (vec tail)]))))

;; ------------------- gate's constants, read live -------------------

(def ^:private gate-constants
  "Every integer constant `credit.kernels.gate` publishes, taken from the
  loaded namespace itself -- the single source of truth this whole test
  compares against."
  (into {} (for [[sym v] (ns-publics 'credit.kernels.gate)
                 :when (integer? @v)]
             [sym @v])))

(defn- inline-constants
  "`form` with every reference to a gate constant replaced by its current
  value -- i.e. gate's source rewritten into what the `.kotoba` port has
  to say, since the port cannot name the constant."
  [form]
  (walk/postwalk #(if (and (symbol? %) (contains? gate-constants %))
                    (get gate-constants %)
                    %)
                 form))

(defn- constants-used [form]
  (into #{} (filter gate-constants) (filter symbol? (tree-seq coll? seq form))))

;; ------------------------------ pins -------------------------------

(deftest kotoba-ports-restate-the-gate-with-current-constants-inlined
  (let [cljc (defns (read-forms (gate-source)))]
    (doseq [[path expected-fns] ported]
      (testing path
        (let [kot (defns (read-forms (kotoba-source path)))]
          (is (= expected-fns
                 (set/intersection (set (keys cljc)) (set (keys kot))))
              (str path " and credit.kernels.gate must still share exactly the"
                   " ported function names -- a rename on either side silently"
                   " stops pinning whatever it dropped"))
          (doseq [fn-name (sort expected-fns)]
            (testing (str fn-name)
              (is (= (inline-constants (get cljc fn-name))
                     (get kot fn-name))
                  (str "`" fn-name "` has drifted between credit.kernels.gate and "
                       path " (compared with gate's named constants substituted"
                       " for their current values -- so a changed threshold shows"
                       " up here as a changed literal)")))))))))

(deftest every-gate-constant-the-ports-inline-is-pinned
  (let [cljc (defns (read-forms (gate-source)))
        used (into #{} (mapcat #(constants-used (get cljc %)))
                   (mapcat val ported))]
    (is (= '#{confidence-floor-x100 affordability-ceiling-x100} used)
        (str "the ported functions must go on referencing exactly these named"
             " constants; if gate stops naming one (or starts naming another),"
             " the port-parity pin above quietly stops covering it"))))

(deftest affordability-kotoba-inlines-the-current-ceiling
  (let [body (get (defns (read-forms (kotoba-source affordability-path)))
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
             " longer credit.kernels.gate/affordability-ceiling-x100 -- this"
             " slice shares no function name with the kernel, so it is pinned"
             " through that coefficient alone"))))

(deftest prose-quotes-of-gate-constants-are-current
  (let [sources (into {gate-path (slurp (gate-source))}
                      (for [path (cons affordability-path (keys ported))]
                        [path (slurp (kotoba-source path))]))
        quotes (for [[path text] sources
                     [_ nm v] (re-seq #"([a-zA-Z][\w?*<>-]*)\s*=\s*(-?\d+)" text)
                     :let [sym (symbol nm)]
                     :when (contains? gate-constants sym)]
                 [path sym (Long/parseLong v)])]
    (is (seq quotes)
        "no source states a gate constant's value in prose any more -- if that
        is deliberate, drop this pin; otherwise the comment was lost")
    (doseq [[path sym v] quotes]
      (is (= (get gate-constants sym) v)
          (str path " still says `" sym " = " v "` in prose, but the constant is"
               " now " (get gate-constants sym))))
    (is (= '#{confidence-floor-x100 affordability-ceiling-x100}
           (set (for [[path sym _] quotes
                      :when (= path "wasm/credit_verdict.kotoba")]
                  sym)))
        (str "wasm/credit_verdict.kotoba's whole reason for inlining is spelled"
             " out in comments naming both constants -- keep them naming both"))))
