# ADR-0003: kotobase.net persistence migration (KV -> langchain.kotoba-db)

- Status: Accepted (2026-07-24)
- Related: `0002-http-edge-loan-intake.md`, `cloud-itonami-commitment-ledger/docs/adr/0004-kotobase-persistence-migration.md` (sibling actor, same migration, same fleet), superproject `90-docs/adr/2607242200-cloud-itonami-isic6492-commitledger-cross-actor-wiring.edn`

## Context

Since V2 this actor's `:application/intake` HTTP surface (`credit.edge.loan-endpoints`) has persisted each new loan application in a hand-rolled Cloudflare KV JSON blob (`credit.edge.kv-store`, deliberately simpler than commitment-ledger's own -- no cross-application ledger-state, since intake touches no other application's state). This ADR replaces that with real kotobase.net (net-kotobase) persistence, mirroring `cloud-itonami-commitment-ledger`'s own identical migration (sibling actor, same fleet, ADR there) and following the pattern `cloud-itonami-isic-5820` (ADR-2607184000) proved once first.

## Decision

Direct mirror of commitment-ledger's own decision record (see that repo's ADR-0004 for the full narrative) -- only the isic-6492-specific deltas are recorded here:

### 1. `credit.store/DatomicStore` becomes `:db-api`-injectable

Same `store-with-api` + `chain` pattern; SIMPLER than commitment-ledger's counterpart since this actor's exposed surface (`:application/intake` only) touches no cross-application state -- only `application`/`all-applications`/`with-applications` needed to become `chain`-aware, no `ledger-state`/`with-ledger-state` equivalent was needed.

### 2. `credit.edge.kotobase-store` -- `KVStore` protocol implementation (2 methods)

`KotobaseKVStore` implements `credit.edge.kv-store`'s OWN `KVStore` protocol (`kv-get-application`/`kv-put-application!` -- this actor's smaller 2-method surface). `credit.edge.loan-endpoints/intake-core!`/`get-application-core` needed ZERO changes; only the `on-request-*` entry points swap `(kv/cloudflare-kv-store env)` for `(kotobase-kv-store-from-env! env)`.

### 3. FIRST self-mint outbound identity for this actor

Unlike commitment-ledger (which already had one for its own isic-6492 calls, ADR-2607242200), isic-6492 had NO self-mint identity before this migration (V3 only added inbound CACAO verify + a caller allow-list). Generated ONE new Ed25519 keypair via `scripts/generate-actor-identity.cljs` (adapted, algorithm-unchanged, from commitment-ledger's own script), stored as the `ISIC6492_ACTOR_SEED` Cloudflare Pages secret + `ISIC6492_ACTOR_DID` public var on this actor's OWN Cloudflare Pages project.

### 4. Numeric identity-attribute fix

`credit.store`'s schema marks `:ledger/seq`/`:disbursement/seq` `:db.unique/identity`, both previously `(count ...)` ints. Fixed the same way as commitment-ledger (str-wrap writes, `parse-seq-num` reads). Defensive: this actor's live HTTP surface never actually exercises `append-ledger!`/`:loan/mark-disbursed` (only `:application/intake` is exposed), but the fix is unconditional per the task's own requirement and is regression-tested (`test/credit/store_numeric_identity_test.cljc`, verified to genuinely fail before the fix, matching commitment-ledger's own verification method).

### 5. Same 3 confirmed-live CACAO wire-format bugs, found first in commitment-ledger, independently reproduced and fixed here

`credit.edge.kotobase-identity`/`credit.edge.cbor` needed the identical fixes commitment-ledger's ADR-0004 documents in full: (1) `:exp` required, (2) envelope `s.t: "EdDSA"` + canonical CBOR key order (via a new `encode-kotobase-cacao-envelope`, `credit.edge.cbor/header`+`encode-text` widened from private to public), (3) `:graph` resource scope must be this actor's own did:key, not a computed CID (`kotobase.cacao.cljs`'s own "apex" profile note) -- eliminating graph-CID-derivation code entirely, `:db_name` alone suffices for both reads and writes.

## Consequences

Same consequences as commitment-ledger's ADR-0004 (see that repo's ADR for the full list): real live kotobase.net persistence; 2 upstream `langchain.kotoba-db` bugs fixed (shared benefit); zero changes to any of the 5 Governor checks or the StateGraph; `kv_store.cljc` remains, unused in production; kotobase.net's eventual-consistency window (~1-2 min) noted as a backend characteristic, not a defect in this migration.

## Verification

- `clojure -M:dev:test`: 89 tests / 739 assertions (up from 82/713), 0 failures.
- `clojure -M:lint`: 0 errors (17 pre-existing-shaped warnings).
- `npx shadow-cljs release edge-api`: compiles cleanly.
- Live: deployed to Cloudflare Pages Production (branch `main`). `POST /api/loan/intake` exercised indirectly via commitment-ledger's own live `:commit`-node fire-and-forget call (the SAME cross-actor wiring ADR-2607242200 already established) using commitment-ledger's real, allow-listed self-mint identity -- proving a real loan application landed on this actor's own kotobase.net-backed store via the real production wiring, not a simulation.

## References

- `cloud-itonami-commitment-ledger/docs/adr/0004-kotobase-persistence-migration.md` (the full shared decision record)
- `orgs/kotoba-lang/langchain/src/langchain/kotoba_db.cljc`
- `orgs/gftdcojp/net-kotobase/kotobase-cf-wasm/src/kotobase_cf_wasm/auth.cljs` (read-only reference, not modified)
