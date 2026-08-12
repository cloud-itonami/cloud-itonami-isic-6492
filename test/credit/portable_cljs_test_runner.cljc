(ns credit.portable-cljs-test-runner
  "PRIMARY automated quality gate for this actor under a real
  ClojureScript host (cljs.main --target node) — the same runtime-
  priority rule as gftdcojp/cloud-itonami's ADR-0016 / the superproject
  CLAUDE.md:

      kotoba wasm runtime  >  clojurewasm  >  ClojureScript  >  nbb
      (JVM / babashka are last-resort compat, not the design target)

  The credit test suite is portable .cljc and runs UNCHANGED here and
  on the JVM (`clojure -M:dev:test`, secondary compat gate). This
  includes `credit.store-contract-test`, which exercises the
  langchain.db Datomic-API-compatible store — the kotoba-server /
  kotobase datom seam — under ClojureScript.

  Since 2026-08-12 it also runs `credit.kernels.kotoba-oracle-portable-
  test`. That is not optional decoration: the governor's rules are now
  executed from a shipped Kotoba core, its nine flags cross as an `:i64`
  record, and an `:i64` record FIELD is coerced differently on
  ClojureScript than on the JVM (`js/BigInt` required, `js/Number`
  rejected). A sibling repo shipped that exact bug green for a day. The
  seam's own JVM test cannot see it, so the question is asked here.

  DELIBERATE EXCLUSION: `wasm.affordability-test` is NOT required or
  run here — it hosts wasm/affordability.wasm via kototama.tender
  (Chicory), a JVM-only WASM host with direct Java interop
  (.readAllBytes / .memory / .writeI32), so it stays a `.clj` file and
  runs only under the JVM gate. The affordability DECISION itself is
  covered under ClojureScript by `credit.kernels.gate-test` (the
  kernel restates the same exact-integer ceiling comparison the
  `.kotoba` guest makes).

  Invoke from the repo root (the :test alias's :main-opts would steal
  -m if combined, hence -Sdeps for the extra path):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' \\
      -M:dev:cljs -m cljs.main --target node \\
      -m credit.portable-cljs-test-runner"
  (:require [clojure.test :as t :refer [run-tests]]
            [credit.facts-test]
            [credit.governor-contract-test]
            [credit.kernels.gate-test]
            [credit.kernels.kotoba-oracle-portable-test]
            [credit.phase-test]
            [credit.registry-test]
            [credit.store-contract-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'credit.facts-test
             'credit.registry-test
             'credit.phase-test
             'credit.kernels.gate-test
             'credit.kernels.kotoba-oracle-portable-test
             'credit.governor-contract-test
             'credit.store-contract-test))
