(ns credit.edge.await-helper
  "The two things a `credit.edge.*` test needs in order to run on BOTH
  runtimes, rather than only on the JVM.

  Every `credit.edge.*` core fn threads through `credit.edge.pcompat`,
  whose `resolved` is `#?(:cljs (js/Promise.resolve v) :clj v)`. So the
  return value of `intake-core!`, `kv-get-application`, `store/application`
  and friends is the VALUE on the JVM and a PROMISE on ClojureScript.

  Tests written against the JVM shape -- destructuring the return value
  where it stands, or reading an atom straight after a call that appends
  to it -- pass on the JVM and quietly report nil on ClojureScript. Four
  namespaces in this repo did exactly that and were excluded from the
  ClojureScript runner for it (measured 2026-08-25: 22 tests / 46
  assertions / 41 failures+errors there, 0 on the JVM). That is root
  ADR-2608730000's shape: a `.cljc` test claiming two runtimes and
  running on one.

  Nothing here is ClojureScript-specific in its MEANING. On `:clj` both
  functions collapse to direct calls, because `pcompat/then` is direct
  there; the JVM run is byte-for-byte the same work it always did."
  (:require #?(:clj  [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true])
            [credit.edge.pcompat :as pc]))

(defn awaiting
  "Run `f` on the value `p` resolves to, and let `cljs.test` wait for it.

  On `:clj` this is `(f p)`. On `:cljs` it returns the `async` value
  `cljs.test` looks for, so the assertions inside `f` run after the
  promise settles AND are counted -- verified 2026-08-25 by breaking one
  assertion inside an `f` and seeing the run report it and exit 1.

  A rejected promise is reported as a failing assertion rather than
  vanishing: a test whose promise rejects must not look like a test that
  passed."
  [p f]
  #?(:clj (f p)
     :cljs (t/async done
             (-> (js/Promise.resolve p)
                 (.then (fn [v] (f v) (done)))
                 (.catch (fn [e]
                           (t/is false (str "promise rejected: " e))
                           (done)))))))

(defn sequentially
  "Call each thunk in `fs` in order, waiting for the promise-like each
  returns before calling the next. Returns promise-like of the last
  result.

  This is what a test needs when it asserts on a SIDE EFFECT -- an atom a
  call appends to -- rather than on a return value. On the JVM the effect
  has already happened when the call returns, so `(is (= [...] @log))`
  written straight after the call is correct there and is a race on
  ClojureScript. Putting the assertion in its own thunk makes the order
  explicit on both runtimes instead of relying on one of them."
  [fs]
  (reduce (fn [acc f] (pc/then acc (fn [_] (f)))) (pc/resolved nil) fs))
