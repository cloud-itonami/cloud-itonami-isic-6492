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

## Fleet deployment (Node.js / `wasm-webcomponent`)

`verify_node.mjs` hosts `affordability.wasm` via `kotoba-lang/wasm-webcomponent`'s
`actor-host.js` (plain Node.js, no JVM) — the same pattern
ADR-2607072530 established for `cloud-itonami-isic-6511`, reused here since
this module needs zero host imports (simpler: no `log-write`/`llm-infer`
wiring).

Run locally:

```sh
node wasm/verify_node.mjs approve        # or: reject | zero-income
node wasm/verify_node.mjs 500000 2000000 6000000   # raw existing-debt/requested-amount/annual-income
```

(needs the sibling checkout `orgs/kotoba-lang/wasm-webcomponent` present,
per the west layout).

**Deployed and verified on a real murakumo fleet node (`asher`)**,
2026-07-07: transferred the compiled `.wasm` + `wasm-webcomponent/src/`
(21 files, 232K) to `/tmp` over `rsync`, ran all three scenarios there with
`node wasm/verify_node.mjs <scenario>`, got results identical to the local
run for each, then removed the transferred files (Node.js itself was
already present on the fleet from the isic-6511 PoC and was left in place,
per that ADR's precedent — not reinstalled or removed here).

## Follow-ups

- This module requests zero host imports (pure arithmetic) — it does not
  exercise the `actor:host` capability-grant path at all. That's still only
  proven by `kotoba-lang/kototama`'s own sha256/gen-keypair fixtures and
  ADR-2607072530's `llm-infer` capability.
- No wire transport (HTTP/XRPC/etc.) puts a host in front of this ABI yet —
  it's callable from a Clojure or Node.js process directly, not over a
  network. That's real product work, out of scope here.
