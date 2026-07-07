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

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.wasm --json
```

## Follow-ups

- `main` is 0-arity with two scenarios hardcoded in (an approve case and a
  reject case, self-checking that both are judged correctly) — there is no
  parameterized-invocation ABI yet for a host to pass real applicant numbers
  in. That's real product work, out of scope here.
- Only proves the JVM/Chicory host path (`kototama.tender`). The
  browser-native path (`wasm-webcomponent`'s `actor-host.js`,
  ADR-2607062400) is not exercised by this module.
- This module requests zero host imports (pure arithmetic) — it does not
  exercise the `actor:host` capability-grant path at all. That's still only
  proven by `kotoba-lang/kototama`'s own sha256/gen-keypair fixtures.
