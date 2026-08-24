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

  Invoke from the repo root. COMPILE, then run the bundle with node --
  do not use `cljs.main -m` (see EXIT CODE below):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' -M:dev:cljs -m cljs.main \\
      --target node --output-dir target/node-out \\
      --output-to target/portable-tests.cjs \\
      -c credit.portable-cljs-test-runner
    echo '{\"type\":\"commonjs\"}' > target/node-out/package.json
    node target/portable-tests.cjs

  EXIT CODE. The two-step above exists because `cljs.main ... -m
  credit.portable-cljs-test-runner` evaluates -main inside a node REPL
  environment, and that host process exits 0 no matter what the tests
  did. Measured 2026-08-25: one deliberately broken assertion, run that
  way, printed `1 failures` and exited 0. `set!`-ing `js/process.exitCode`
  from :end-run-tests -- which this runner did, and which reads like a
  working failure signal -- cannot survive that, and calling
  `js/process.exit` hangs the driver instead. So for as long as this
  file has existed, its own failure signal has been unreadable by any
  caller that checks `$?`: the ADR-2608136000 shape, a check that could
  not report failure returning the same value as one that passed.

  The compiled bundle is an ordinary node program, so `process.exitCode`
  works there. Verified in BOTH directions on 2026-08-25: unmodified,
  63 tests / 641 assertions / 0 failures / exit 0; with one assertion
  broken INSIDE an async callback (the case that lands after run-tests
  has already returned), `1 failures` and exit 1.

  The `package.json` line is not incidental. This repo's package.json
  says `\"type\": \"module\"`, which makes node read every emitted .js
  under target/node-out as ESM and die on Closure's `require`. The
  marker file scopes those back to CommonJS."
  (:require [clojure.test :as t :refer [run-tests]]
            [credit.facts-test]
            [credit.governor-contract-test]
            [credit.kernels.gate-test]
            [credit.kernels.kotoba-oracle-portable-test]
            [credit.phase-test]
            [credit.registry-test]
            [credit.edge.auth-test]
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
  同じ 4 つが JVM では通る（全体で 111 tests / 877 assertions / 0 failures）。

  **`credit.edge.auth-test` は 2026-08-25 に書き直して除外を解いた。** 6 本とも
  `awaiting`（`:clj` では `(f p)`、`:cljs` では `cljs.test/async`）を通すので、
  両 runtime で同じ assertion が走る。JVM 側は 111/877/0 のまま動かない。
  残る 3 つは同じ書き換えを待っている —— 除外は理由が真である間だけ有効で、
  下の再検査がそれを毎回確かめる。"
  '{credit.edge.kv-store-test        "promise-like を同期的に分配束縛している"
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
             'credit.edge.auth-test
             'credit.edge.caller-allowlist-test))

;; The compiled node bundle runs `cljs.nodejscli`, which calls whatever
;; `*main-cli-fn*` names. Without this the bundle loads every namespace,
;; runs no test, and exits 0 -- measured 2026-08-25, and indistinguishable
;; from a clean run in both the output and the exit code.
#?(:cljs (set! *main-cli-fn* -main))
