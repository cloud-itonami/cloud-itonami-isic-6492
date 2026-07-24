# ADR-0002: HTTP edge exposure -- intake + read only, never the rest of the lifecycle

## Status

Accepted. Additive to ADR-0001 (unchanged): all 5 existing Governor
checks, `credit.phase`'s no-auto-commit-past-intake invariant, and the
`MemStore`/`DatomicStore` `Store` contract are all unmodified. This ADR
covers only what this HTTP edge layer adds on top.

## Context

`cloud-itonami-isic-6492` (`credit.*`) was, until now, a pure library --
`Credit-LLM` + `Credit Governor`, tested via `clojure -M:dev:test`, no
HTTP exposure at all. A sibling actor in this fleet,
`cloud-itonami-commitment-ledger` (`commitledger.*`), needs to create a
disbursement-side application on this actor's books after its own
`:commitment/record` commits -- see that repo's own
`docs/adr/0003-isic6492-wiring-and-approval-resume.md` for the full
cross-actor design.

The critical constraint driving this ADR: isic-6492's real loan
lifecycle is 5 stages (`:application/intake` -> `:jurisdiction/assess`
-> `:creditworthiness/screen` -> `:loan/approve` -> `:loan/disburse`).
Per `credit.phase`, only `:application/intake` is ever auto-eligible
(phase 3's `:auto` set has exactly one member) -- the other four ALWAYS
require this actor's OWN human approval via its OWN Governor/actor
graph. **commitment-ledger must never auto-drive this actor past
intake** -- doing so would bypass this actor's independent governance,
violating this fleet's "no actor trusts another's self-report /
independent governance" invariant.

## Decision

### Decision 1: intake + read only, by construction

`src/credit/edge/loan_endpoints.cljc` exposes exactly two routes:
`POST /api/loan/intake` (create, runs the EXISTING, UNMODIFIED
`credit.operation/build` StateGraph for `:application/intake`, which
per `credit.phase` auto-commits when the Governor is clean) and
`GET /api/loan/{id}` (read). `:jurisdiction/assess`/`:creditworthiness/
screen`/`:loan/approve`/`:loan/disburse` are NEVER driven by this edge
layer -- there is no code path here that can reach them. This is a
permanent, structural scope boundary, not a phase-1 rollout milestone.

### Decision 2: a NEW, edge-layer-specific allow-list check, separate from `credit.governor`

`src/credit/edge/caller_allowlist.cljc` is a HARD check requiring the
verified CACAO's `iss` (a did:key) to be a member of a configured
allow-list (`env.KNOWN_CALLER_DIDS`, comma-separated). This is
DELIBERATELY separate from `credit.governor`'s existing 5 checks (not
added there, not renumbered into them) -- see that file's own docstring
for why: a validly-signed CACAO (`credit.edge.cacao/verify`) proves
AUTHENTICITY of the sender, not AUTHORIZATION to call this specific
integration point. Any party can self-mint a fresh Ed25519 identity and
present a perfectly valid CACAO; the allow-list is defense-in-depth
specific to this cross-actor wiring, not a general-purpose access-
control system, and `credit.governor`/`credit.phase`/`credit.operation`
are entirely unmodified and unaware this file exists.

### Decision 3: ported, not required, wire-format code -- same convention as commitment-ledger's own precedent

`src/credit/edge/{cacao,base58,cbor}.cljc` are direct, faithful PORTS of
`cloud-itonami-commitment-ledger`'s own `commitledger.edge.{cacao,
base58,cbor}` (itself a port of `cloud_itonami.edge.{cacao,base58,cbor}`,
`orgs/gftdcojp/cloud-itonami`, AGPL-3.0-or-later, license-compatible --
this repo already carries the same license) -- copied into this repo,
not cross-repo required. This mirrors the SAME local-mirror convention
`commitledger.edge.*` established for the identical files one hop
upstream; this is now a THIRD copy in the fleet of the same, unchanged
algorithm. `credit.edge.pcompat`/`credit.edge.auth` similarly mirror
`commitledger.edge.pcompat`/`commitledger.edge.auth`'s own shape (the
async seam + `CacaoVerifier` injection seam), minus `commitledger.edge.
auth`'s resource-scope/lender-identity checks (this actor's own caller-
authorization gate is Decision 2's allow-list instead -- see `credit.
edge.auth`'s ns docstring for why the shape differs).

### Decision 4: `GET /api/loan/{id}` is gated, unlike commitment-ledger's public GET

`commitledger.edge.commitment-endpoints/on-request-get-application` is
public (redacting exactly two fields: `:lender/id`/`:borrower-did`).
This actor's application record instead carries real applicant/income/
credit-score PII with no redaction scheme designed for it yet.
Defaulting to gated-read (same CACAO + allow-list gate as intake) is
the safer choice until a redaction scheme is designed -- a documented
choice, not an oversight. The primary consumer is the calling actor
itself, confirming its own just-created intake succeeded (exactly the
shape `cloud-itonami-commitment-ledger`'s own end-to-end proof uses).

### Decision 5: simpler KV persistence than commitment-ledger's own -- deliberately, given the narrower exposed op surface

`src/credit/edge/kv_store.cljc` mirrors `commitledger.edge.kv-store`'s
protocol + Mem/Cloudflare split PATTERN, but is deliberately simpler:
no `ledger-state` cross-application aggregate, no `index` key. Every
Governor check this edge surface can ever trigger (`:application/
intake`'s proposal always has `:stake nil`, `:confidence 0.97` fixed,
and none of the 5 checks -- spec-basis / evidence-incomplete /
application-not-approved / affordability-exceeded / double-disbursement
-- ever fire for `:application/intake`) needs no cross-application
ground truth, unlike commitment-ledger's check 6 (individual-lender-
loan-count) or its double-release guard. `credit.edge.loan-endpoints/
intake-core!` therefore builds a brand-new, empty, single-application
`credit.store/MemStore` per request (a CREATE has no pre-existing state
to rehydrate) via the record's own public generated constructor
(`credit.store/->MemStore`), rather than modifying `credit.store` to
add an `empty-store` export.

## Consequences

- (+) `cloud-itonami-commitment-ledger` (or any other trusted caller)
  can now create a real disbursement-side application on this actor's
  books, without this actor ever ceding control over its own
  underwriting/disbursement decisions.
- (+) The Governor's 5 checks, the Phase table, and `credit.operation`
  are entirely unmodified -- proven by the full existing test suite (82
  tests / 713 assertions after this ADR's additions, up from the
  pre-existing suite, all passing unmodified) plus new coverage for the
  edge layer itself.
- (+) The allow-list closes a real gap CACAO verification alone leaves
  open (authenticity != authorization for this specific endpoint).
- (-) `:jurisdiction/assess`/`:creditworthiness/screen`/`:loan/approve`/
  `:loan/disburse` remain entirely unreachable over HTTP -- an operator
  who wants a live full-lifecycle HTTP surface for THIS actor needs a
  separate, explicitly-scoped follow-up (not attempted here, by design).
- (-) `GET /api/loan/{id}`'s gated-not-public posture means a caller
  needs a CACAO + allow-list membership even for a read -- less open
  than commitment-ledger's own public GET, an explicit trade-off for
  this actor's more sensitive PII shape (Decision 4).

## Verification

- `clojure -M:dev:test` -- 82 tests / 713 assertions, 0 failures, 0
  errors (up from the pre-existing suite; ALL prior tests, including
  the JVM-only `wasm.*` kototama/Chicory suite, pass unmodified).
- `clojure -M:lint` -- 0 errors (17 warnings, all the same class of
  CLJS/JVM host-conditional false positive `commitledger.edge.*`'s own
  `.clj-kondo/config.edn` already documents and downgrades to warning).
- `npx shadow-cljs release edge-api` compiles cleanly.
- Deployed live to a NEW Cloudflare Pages project (`cloud-itonami-
  isic-6492`, `https://cloud-itonami-isic-6492.pages.dev`) with a NEW,
  dedicated KV namespace (`ISIC6492_LOAN_KV`) -- never reuses
  commitment-ledger's own KV namespace.
- End-to-end proven live: a real `POST /api/loan/intake` call from
  `cloud-itonami-commitment-ledger`'s own fire-and-forget isic-6492
  client (triggered by that actor's real `POST /api/commitment/{id}/
  approve` resuming a real interrupted `:commitment/record` run)
  created a real application (`loan-8c78r09n`), independently confirmed
  via `GET /api/loan/loan-8c78r09n` -- see `cloud-itonami-commitment-
  ledger`'s own `docs/adr/0003-isic6492-wiring-and-approval-resume.md`
  for the full curl transcript.

## References

- `docs/adr/0001-architecture.md` (this repo's own original architecture
  ADR -- unchanged).
- `cloud-itonami-commitment-ledger` `docs/adr/0002-http-edge-live-
  registry-verification.md` -- the sibling ADR this one's Decisions 3/5
  mirror the conventions of (ported wire-format code, KV persistence
  pattern).
- `cloud-itonami-commitment-ledger` `docs/adr/0003-isic6492-wiring-and-
  approval-resume.md` -- the cross-actor wiring this ADR is the other
  half of.
- `90-docs/adr/<superproject-adr-id>-cloud-itonami-isic6492-commitledger-
  cross-actor-wiring.edn` (superproject) -- the fleet-level ADR
  recording this cross-actor wiring's independent-governance rationale.
