# ADR-0004: the governor decides by the shipped `.kotoba` core

- Status: Accepted (2026-08-12)
- Related: superproject `90-docs/adr/2608112100-a-kotoba-core-with-a-parity-test-is-not-done.edn` (what "migrated" means), `90-docs/adr/2608120200-what-the-kotoba-cores-actually-run.edn` (the survey that named this repo), `0001-architecture.md`, `wasm/README.md`

## Context

This actor had three `.kotoba` files under `wasm/`, a host that could run
two of them (`credit.kernels.gate-kotoba`, via `kototama.tender`), and a
test proving they agreed with `credit.kernels.gate` on all 52 battery
cases. It looked migrated. It was not: that host's own docstring said
"that swap is NOT done here", and every credit decision this actor made
was made by the Clojure in `credit/kernels/gate.cljc`.

ADR-2608112100 states the completion condition — a `.kotoba` core with a
parity test is not migrated; the host has to execute the shipped
artifact. ADR-2608120200 surveyed 23 repos against it and listed this one
among five dangerous shapes, for a specific reason:

> The `.kotoba` subset has no top-level `def`, so `confidence-floor-x100`
> 60 and `affordability-ceiling-x100` 43 are written into the bodies.
> Editing the constant in the `.cljc` does not reach the core.

A gate landed on 2026-08-12 (`d3a129a`) that kept the literals equal to
the `.cljc` constants. That closed the drift, and left the decision where
it was. It also measured the wider hole: **changing a threshold in a
`.kotoba` left all 93 tests green**, because the suite exercised a
committed `.wasm` and nothing recompiled the source.

## Decision

### 1. The rules move to `src/credit/kernels/gate.kotoba`, and are executed

`credit.kernels.gate` no longer contains the governor verdict, the phase
table, the confidence floor or the affordability ceiling. It builds an
argument, calls `credit.kernels.kotoba-oracle`, and reads back an
integer. `credit.governor/check` and `credit.phase/gate` are unchanged
and now decide by the core.

### 2. The thresholds are asked for, not restated

They are 0-arity exports of the core, and the `.cljc` vars are the calls:

```clojure
(def confidence-floor-x100 (oracle/call-i64 :gate 'confidence-floor-x100 []))
```

This is the difference between the gate that landed on 2026-08-12 and
this change. There is no second number to keep equal. The existing pins
in `credit.kernels.gate-test` — which tie `credit.governor/confidence-
floor` and `credit.registry/affordability-ceiling` to these vars —
therefore now tie those façade doubles to the shipped artifact.

### 3. The delegated artifact is KIR, not the `.wasm` this repo ships

Three reasons, in the order they bind:

1. **The decision path is not the JVM.** The primary gate is
   ClojureScript and the deployed surface is a Cloudflare Worker
   (`shadow-cljs.edn` `:edge-api`, reaching the governor through
   `credit.operation`). `kototama.tender` is JVM-only (Chicory, Java
   interop). Delegating to it would have moved the authority onto the one
   runtime that does not serve traffic.
2. **A `.wasm` cannot be asked for a constant.** One entry point, inputs
   in linear memory. The thresholds could only stay literals — the defect
   being closed.
3. It is the seam `cloud-itonami-app`, `kotoba-lang/crdt` and
   `kotoba-lang/com-cloudflare` already use.

Measured against ADR-2608112100's boundary first: all three `.kotoba`
files take i32 **scalars only** — `affordability` 3, `credit_phase` 3,
`credit_verdict` 9 — with no collection inside the guest. Nothing here
grows with the domain, so all three cross. (The nine flags reach the
guest as one fixed record, because the compiler's `max-parameters` is 5
on pin 806f5cef.)

### 4. The artifact is a generated `.cljc`, not an EDN resource

The four existing seams read `resources/**/*.kir.edn` off the classpath.
A Cloudflare Worker has no classpath and no filesystem, and neither does
`cljs.main --target node` outside its compile unit — an EDN resource
would be readable on exactly the runtime that does not serve traffic. So
`credit.kernels.kotoba-oracle-gen` writes `src/credit/kernels/
gate_kir.cljc`, one generated namespace holding the compiled KIR as one
datum. Still one artifact, still generated, still gated.

`io.github.kotoba-lang/kotoba-kir` becomes a runtime dependency, pinned
to `d58972da` — the sha the pinned compiler (`806f5cef`, test-only)
declares. The compiler never reaches a consumer.

### 5. The `wasm/` files stay, as a second execution path

They decide nothing. `credit.kernels.gate-kotoba` and the three
`test/wasm/*_test.clj` are the only things that reach them. They are kept
because a second implementation on a different execution path that agrees
is worth having, and two gates keep them from drifting away in silence.

## Gates, and the mutations that made them fail

| gate | catches |
|---|---|
| `kotoba-oracle-test/the-shipped-artifact-is-the-current-source-compiled` | the artifact is not the source compiled — WHOLE document, every export |
| `kotoba-oracle-test/the-host-reads-the-artifact-…` + `…the-facades-follow-…` | a host that kept its own copy of any rule |
| `kotoba-oracle-test/a-missing-core-throws-…` | a silent fallback |
| `gate-kotoba-test` | the legacy `.wasm` disagreeing with the core (52 cases) |
| `gate-kotoba-inline-test` | the legacy literals, and prose, disagreeing with the artifact's thresholds |

Every one was run against a deliberate break, on `clojure -M:dev:test`
(**106 tests / 845 assertions / 0 failures** unmutated):

| mutation | result |
|---|---|
| ceiling 43 → 36 in the `.kotoba`, **not** regenerated | **1 failure** — drift. This is the case ADR-2608120200 measured as leaving all 93 tests green |
| ceiling 43 → 36 **and** regenerated | **11 failures** across the façade pin, the ceiling boundary tests, the battery lock, the legacy-port pins and the prose pin — i.e. the number really does flow from the artifact out to `credit.registry` |
| shipped artifact: `op-auto-enabled`'s `(= op 1)` → `(= op 5)` (disburse auto-commits) | **31 failures**, drift among them |
| shipped artifact: one hex digit of `:schema-identities`, a field nothing executes | **1 failure — the drift gate alone.** Whole-document coverage, not one export (`com-cloudflare` found gating one core out of nine let a production mutation pass 94 tests) |
| `credit.kernels.gate/phase-disposition` reverted to a faithful host copy | **2 failures, both in the delegation gate.** Every behavioural test still passed — which is the point: a host copy is what they were written against |

## The ClojureScript asymmetry, measured rather than assumed

A green JVM suite is not evidence about this actor's deploy runtime. Two
ways a delegated seam can be green on the JVM and broken on ClojureScript
were measured in this fleet on the same day, and this repo has both
shapes:

1. **`:i64` inside a record.** `kir/execute` coerces a top-level `:i64`
   argument, but a record FIELD goes through
   `kotoba.kir.value/bounded-typed-value!`, which on `:cljs` requires a
   `js/BigInt` and rejects a `js/Number`. `kotoba-lang/calendar` shipped
   exactly that and its delegated `overlaps?` threw `value is not a
   signed i64` on ClojureScript for a day behind a green JVM suite. The
   entire governor verdict here crosses as a nine-field `:i64` record.
2. **Integer-to-string formatting** via the synthesised
   `__kotoba_string_from_i64`, which at older kir pins guards with
   `(integer? start)` — false for a BigInt.

Handled: `credit.kernels.kotoba-oracle/record` converts every field with
`i64`, and `i64-value` converts every result back. (2) cannot arise —
the core contains no string and every export returns `:i64` — and
`credit.kernels.kotoba-oracle-portable-test` asserts that rather than
assuming it.

The asymmetry was then reproduced deliberately, by dropping the field
conversion in `oracle/record`:

| runtime | result |
|---|---|
| `clojure -M:dev:test` | **111 tests / 877 assertions / 0 failures — green** |
| `cljs.main --target node` | **errors: `value is not a signed i64`** in `battery-lock`, `confidence-floor-boundary`, `out-of-range-confidence-fails-closed` |

Unmutated, both are green: JVM 111/877, ClojureScript **49 tests / 599
assertions / 0 failures**. `credit.portable-cljs-test-runner` now
includes `credit.kernels.kotoba-oracle-portable-test`, which asks the
record-crossing, the result-conversion, the no-string property and a
substituted-core delegation check on the runtime that deploys — the last
one without a compiler, by editing a function body in the shipped KIR as
data.

## Consequences

- `credit.kernels.gate`'s public API is unchanged, so `credit.governor`
  and `credit.phase` were not edited.
- Every governor verdict now runs the KIR interpreter in-process. This is
  interpretation, not tender: `kotoba/pure`, no capability, no
  supervisor. There is nothing to gate because the core takes no effect.
- `wasm/README.md` now opens with what actually runs (ADR-2608120200
  decision 1).
- Not done: the `wasm/` sources are not regenerated from
  `src/credit/kernels/gate.kotoba` — they are a different dialect
  (legacy emitter, no `def`, no records) and converting them would remove
  the independence that is their only remaining value.
