# wasm/ — kotoba-wasm deployment of the affordability check

`affordability.kotoba` is a port of `credit.registry/compute-debt-to-income-ratio`
+ `affordability-ceiling` (the 0.43 back-end debt-to-income ceiling, mirroring
the U.S. Ability-to-Repay/Qualified Mortgage rule, Regulation Z 12 CFR
§1026.43 — see `src/credit/registry.cljc`) into the minimal `.kotoba`
language subset, compiled to a real WASM module via `kotoba wasm emit`, and
hosted via `kototama.tender` (`test/wasm/affordability_test.clj`).

This is the first cloud-itonami actor logic proven through the
`kotoba wasm emit` → `kototama.tender` pipeline — previously that pipeline
only had E2E proof via `kotoba-lang/kototama`'s own demo fixtures
(ADR-2607062330 addendum 5).

## Why the source differs from `credit.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` — no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Uses plain positional args instead of `{:keys [...]}` map destructuring
  (no maps in the wasm-compilable subset).
- Replaces `(pos? annual-income)` with `(> annual-income 0)`.
- Replaces the three `throw`/`ex-info` precondition guards with returning
  `0` (falsy) instead — a WASM export can't throw a JVM exception.
- Compares `100 * (existing-debt + requested-amount) <= 43 * annual-income`
  instead of dividing to get a ratio and comparing to `0.43` — avoids
  floating point entirely (integer cross-multiplication), consistent with
  `kotoba-card`/`kotoba-banking`'s own convention of representing amounts as
  plain integers in the smallest currency unit.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead — the same convention
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` uses. A host
writes three little-endian i32 values (cents) before calling `main()`:

| offset | field              |
|--------|--------------------|
| 0      | `existing-debt`    |
| 4      | `requested-amount` |
| 8      | `annual-income`    |

`main()` returns `1` (affordable) or `0` (not affordable, including
non-positive income). Both offsets are well below `heap-base` (2048), so
they never collide with anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.wasm --json
```

## Fleet deployment (nbb / ClojureScript-on-Node / `wasm-webcomponent`)

`verify_node.cljs` hosts `affordability.wasm` via `kotoba-lang/wasm-webcomponent`'s
`actor-host.js` (plain Node.js, no JVM, run through `nbb` — ClojureScript
without a build step). This started as a plain `.mjs` script; per this
monorepo's `kotoba wasm > clojurewasm > cljs > nbb > jvm` runtime priority
(root CLAUDE.md), it's `.cljs`/`nbb` instead of raw JavaScript everywhere
else in the codebase does the same kind of lightweight Node scripting
(`svgraph/bin/svgraph.cljs`, `kototama/web/generate.cljs`, etc.). Reuses
the pattern ADR-2607072530 established for `cloud-itonami-isic-6511`,
simpler here since this module needs zero host imports (no
`log-write`/`llm-infer` wiring).

`nbb` is a local devDependency (`wasm/package.json`, not a global install —
`npm install` once inside `wasm/`).

Run locally:

```sh
cd wasm && npm install   # once, installs nbb into wasm/node_modules
nbb verify_node.cljs approve        # or: reject | zero-income
nbb verify_node.cljs 500000 2000000 6000000   # raw existing-debt/requested-amount/annual-income
```

(needs the sibling checkout `orgs/kotoba-lang/wasm-webcomponent` present,
per the west layout — `verify_node.cljs` requires `actor-host.js` by
relative path, same as the original `.mjs` did).

**Deployed and verified on a real murakumo fleet node (`asher`)**,
2026-07-07: transferred the compiled `.wasm`, `wasm-webcomponent/src/`, and
`wasm/node_modules` (2.5M total) to `/tmp` over `rsync`, ran all three
scenarios there with `./node_modules/.bin/nbb verify_node.cljs <scenario>`,
got results identical to the local run for each, then removed the
transferred files (Node.js itself was already present on the fleet from
the isic-6511 PoC and was left in place, per that ADR's precedent — not
reinstalled or removed here).

## Resident HTTP host (`server.cljs`) — replaces `asher`'s Rust `kotoba-server`

`server.cljs` wraps the same `actor-host.js` ABI `verify_node.cljs` uses in
a persistent `node:http` listener (`nbb server.cljs [port]`, default 8479)
instead of a one-shot CLI call:

- `GET /health` — liveness probe.
- `POST /isic-6492/affordability` — body
  `{"existingDebt":..,"requestedAmount":..,"annualIncome":..}` (cents) →
  `{"ok":true,"result":0|1,"affordable":bool,"input":{...}}`.

**Deployed resident on 5 of the 10 murakumo fleet nodes** (`asher`,
`naphtali`, `judah`, `zebulun`, `issachar`), 2026-07-08: installed as a new
macOS LaunchDaemon (`com.murakumo.cljc-isic-6492`, `RunAtLoad`+`KeepAlive`,
`/opt/homebrew/bin/node .../node_modules/.bin/nbb server.cljs 8479`) on
each, which **replaces** that node's Rust `com.murakumo.kotoba-mesh` daemon
— the first cloud-itonami wasm actor that's genuinely resident (survives
process death via `KeepAlive`, serves indefinitely) rather than
one-off-verified. `judah`/`zebulun`/`issachar` needed `brew install node`
first (no Node.js there before this). The Rust `kotoba-server`/`kotoba`
binaries and their LaunchDaemon plists have since been **pruned** on all 5
nodes (not just stopped) — reverting now needs `murakumo`'s own
provisioning flow, not a simple `launchctl bootstrap`. `simeon`/`levi`/
`joseph`/`dan` were unreachable (SSH timeout) and `benjamin` reaches but
its login shell errors out — not yet migrated. See ADR-2607082000 (and its
2026-07-08 addendum) for the full record, verification, and consequences
(these 5 nodes drop out of the fleet's libp2p mesh and the
ADR-2607072400 kaisha pod stops working on them — the still-unreachable
nodes are untouched, still Rust).

**nbb `.then().catch()` bug found**: chaining `.catch` after `.then` threw
a runtime `Could not find instance method: catch` inside the real
nested-callback HTTP-handler shape (isolated minimal repros of the same
shape did NOT reproduce it — root cause not fully pinned). Worked around
throughout `server.cljs` by using the two-arg `.then(onFulfilled,
onRejected)` form instead of `.then().catch()` chains everywhere.

## `credit_verdict.kotoba` / `credit_phase.kotoba` — the governor's own decision core (2026-07-14, ADR-2607141900 / ADR-2607150000)

`affordability.kotoba` above ports only the affordability sub-check.
`credit_verdict.kotoba` and `credit_phase.kotoba` go further and port the
governor's actual DECISION LOGIC — `credit.kernels.gate/verdict-code` (+
its `hard-violation`/`affordability-exceeded`/`confidence-low` and `or2`/
`or3`/`or5`/`and2`/`norm-flag`/`not-flag` combinator dependencies) and
`credit.kernels.gate/phase-disposition` + `phase-reason` (+
`op-write-enabled`/`op-auto-enabled`) — per ADR-2607141900's approved
narrow-slice pattern (governor decision logic only, no atoms/records/
facades/external state; `credit.governor/check`'s full façade, which reads
mutable `store` state and builds string `:detail` messages, remains
correctly out of scope). `credit.kernels.gate.cljc` was ALREADY written in
this "safe-kotoba subset" style (pure integer arithmetic, nested `if`, no
keywords/maps/atoms) specifically to keep this door open without a
rewrite — porting it required zero restructuring, only inlining its two
named constants (`confidence-floor-x100` = 60, `affordability-ceiling-
x100` = 43) as literals, since `kotoba-lang/kotoba`'s `wasm-binary` only
recognizes top-level `ns`/`defn` (a plain `def` is silently ignored, not
an error) — same convention `affordability.kotoba` already established.

Two files, not one, because a `kotoba-lang/kotoba` wasm module exports
exactly one entry point (`main`) — `credit_verdict.kotoba` covers the
governor verdict (`main` returns 0 ok / 1 escalate / 2 hard-hold, reading
9 i32 inputs from memory), `credit_phase.kotoba` covers the phase gate
(`phase-disposition` and `phase-reason` share input shape and branch
structure one-for-one, so rather than duplicate `op-write-enabled`/
`op-auto-enabled` across two modules, `main` packs both into one return
value: `10*disposition + reason`, unpacked by the host via `quot`/`rem`).
See each file's own `ns` docstring for the exact memory-offset ABI.

**Verified two ways**: (1) `clojure -M:test` — `test/wasm/
credit_verdict_test.clj` and `test/wasm/credit_phase_test.clj` host the
compiled `.wasm` via `kototama.tender` (same pattern as `wasm.
affordability-test`), with every case copied verbatim from `credit.
kernels.gate.cljc`'s own executable `battery` (52 cases: 21 verdict + 10
afford + 21 phase) — **57 tests / 596 assertions total, 0 failures**
(the existing suite, including `credit.kernels.gate-test`'s own in-process
battery run, is unaffected). `clojure -M:lint`: 0 errors, 0 warnings.
(2) Independently, before committing, both `.kotoba` sources were compiled
and run through `kotoba-lang/kotoba`'s own real `wasm-binary` + Chicory
execution pipeline directly (not via `kototama.tender`) against the same
52 battery cases — 0 failures, confirming the port doesn't depend on any
`kototama`-specific behavior.

**Rebuilding**:

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6492/wasm/credit_verdict.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6492/wasm/credit_verdict.wasm --json
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6492/wasm/credit_phase.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6492/wasm/credit_phase.wasm --json
```

**Not done here (honest scope)**: no fleet deployment (`verify_node.cljs`/
`server.cljs` wiring, murakumo LaunchDaemon rollout) for either new
module — this pass only proves the governor's decision core compiles and
runs correctly as `.kotoba`/WASM, mirroring how ADR-2607072600 (compile +
verify) and ADR-2607082000 (fleet deploy) were kept as separate steps for
the affordability check. `credit.governor/check`'s full façade (mutable
`store`, string `:detail` messages, fact-catalog lookups) remains
correctly unported per ADR-2607141900's decision — only the pure decision
core moves to `.kotoba`, the façade stays JVM/CLJS Clojure and would call
into the compiled WASM (not shown here) the same way a host would.

## Follow-ups

- This module requests zero host imports (pure arithmetic) — it does not
  exercise the `actor:host` capability-grant path at all. That's still only
  proven by `kotoba-lang/kototama`'s own sha256/gen-keypair fixtures and
  ADR-2607072530's `llm-infer` capability.
- `server.cljs` hardcodes a single route for a single actor — not a
  general dispatcher. Plain HTTP, no auth/TLS — fine for this experiment,
  not production-ready.
- Only `asher` runs the cljc/nbb daemon; the other 9 fleet nodes are still
  Rust `kotoba-server`. Fleet-wide rollout, and restoring mesh/kaisha-pod
  function on `asher`, are both out of scope here.
- Root-causing the nbb `.catch` bug (report upstream?) is unstarted.
