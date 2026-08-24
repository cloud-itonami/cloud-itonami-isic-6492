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
            [credit.edge.caller-allowlist-test]
            [credit.store-contract-test]
            [credit.store-numeric-identity-test]
            [credit.edge.pcompat :as pc]))

(def excluded
  "namespace -> なぜこの runner に載せないか。

  4 つとも `.cljc` で、**JVM では通り ClojureScript では落ちる**。原因は 1 つで、
  ソースではなくテスト側にある: `credit.edge.*` の関数は ns docstring どおり
  promise-like を返し、`credit.edge.pcompat/resolved` は
  `#?(:cljs (js/Promise.resolve v) :clj v)` である。テストは戻り値をその場で
  分配束縛しており、JVM ではそれが値なので通り、ClojureScript では Promise
  なので `:ok?` も `:status` も nil になる。

  直すにはこの 4 namespace を `cljs.test/async` + `pc/then` に書き直す必要が
  あり、それはテストの書き換えであってこの runner の一行では済まない。
  ここに書いておくのは、走らないことが忘れられないようにするためである。

  実測 2026-08-25、走らせたときの内訳:
    credit.edge.auth-test            6 tests /  14 assertions / 13 failures
    credit.edge.kv-store-test        4 /   4 /  3 failures
    credit.edge.kotobase-store-test  4 /   8 /  7 errors
    credit.edge.loan-endpoints-test  8 /  20 / 18 failures, 1 error
  同じ 4 つが JVM では通る（全体で 111 tests / 877 assertions / 0 failures）。"
  '{credit.edge.auth-test            "promise-like を同期的に分配束縛している"
    credit.edge.kv-store-test        "同上"
    credit.edge.kotobase-store-test  "同上"
    credit.edge.loan-endpoints-test  "同上"})

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

;; 除外の再検査。理由は「cljs では pcompat/resolved が Promise を返すから
;; 同期的な分配束縛が効かない」なので、それが今も真かをここで確かめる。
;; pcompat が cljs でも同期値を返すようになるか、テストが async に書き直されたら、
;; この entry は成り立たなくなる。
#?(:cljs
   (when-not (instance? js/Promise (pc/resolved {:ok? true}))
     (println (str "STALE EXCLUSION: credit.edge.* を除外している理由は "
                   "pcompat/resolved が cljs で Promise を返すことだが、"
                   "もうそうではない。entry を退役させて suite に入れること。"))
     (set! (.-exitCode js/process) 1)))

(defn -main []
  (run-tests 'credit.facts-test
             'credit.registry-test
             'credit.phase-test
             'credit.kernels.gate-test
             'credit.kernels.kotoba-oracle-portable-test
             'credit.governor-contract-test
             'credit.store-contract-test
             'credit.store-numeric-identity-test
             'credit.edge.caller-allowlist-test))
