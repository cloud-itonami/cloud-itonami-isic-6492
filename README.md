# cloud-itonami-isic-6492

Open Business Blueprint for **ISIC Rev.5 6492**: Other credit granting.
This repository publishes a consumer/commercial credit-granting actor
-- loan-application intake, creditworthiness screening, underwriting
approval and loan disbursement -- as an OSS business that any
qualified, licensed lender can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612)).
Here it is **Credit-LLM ⊣ Credit Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> creditworthiness summary, normalizing loan-application intake, and
> running the affordability arithmetic -- but it has **no notion of
> which jurisdiction's truth-in-lending disclosure requirements are
> official, no license to disburse real loan funds, and no way to know
> on its own whether an applicant's own debt-to-income ratio actually
> exceeds a responsible-lending ceiling**. Letting it disburse a loan
> directly invites fabricated jurisdiction citations, silently
> irresponsible lending a borrower would actually be bound to, and
> liability for whoever runs it. This project seals the Credit-LLM
> into a single node and wraps it with an independent **Credit
> Governor**, a human **approval workflow**, and an immutable **audit
> ledger**.

## Scope: what this actor does and does not do

This actor covers loan-application intake through underwriting
approval and disbursement, gated by an affordability check on the
application's own debt-to-income ratio. It does **not**, by itself,
hold a license to grant credit in any jurisdiction, and it does not
claim to. It also does **not** model a full underwriting decision --
no credit-score weighting, no loan-purpose/collateral analysis, no
payment-history review (see `credit.registry/compute-debt-to-income-
ratio`'s own docstring for the honest simplification this makes: a
single debt-to-income ratio check against a real, well-known
regulatory reference ceiling, not a full underwriting model). Whoever
deploys and operates a live instance (a licensed lender) supplies the
jurisdiction-specific license, the real underwriting expertise and the
real loan-servicing/banking integrations, and bears that
jurisdiction's liability -- the software supplies the governed, spec-
cited, audited execution scaffold so that operator does not have to
build the compliance layer from scratch for every new market.

### Actuation

**Disbursing real loan funds is never autonomous, at any phase, by
construction.** Two independent layers enforce this (`credit.
governor`'s `:actuation/disburse-loan` high-stakes gate and `credit.
phase`'s phase table, which never puts `:loan/disburse` in any phase's
`:auto` set) -- see `credit.phase`'s docstring and `test/credit/
phase_test.clj`'s `loan-disburse-never-auto-at-any-phase`. The actor
may draft, check and recommend; a human lender is always the one who
actually disburses a loan. Like `6511`/`6621`/`6629`/`6612`, this actor
has exactly ONE actuation event.

## The core contract

```
application intake + jurisdiction facts (credit.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Credit-LLM   │ ─────────────▶ │ Credit Governor            │  (independent system)
   │  (sealed)    │  + citations    │ spec-basis · evidence-      │
   └──────────────┘                 │ incomplete · application-    │
                             commit ◀────┼──────────▶ hold │ not-approved ·
                                 │             │           │ affordability-exceeded
                           record + ledger  escalate ─▶ human   (recomputed from the
                                             (ALWAYS for         application every time,
                                              :loan/disburse)     no proposal/stored-
                                                                  verdict lookup) ·
                                                                  double-disbursement
```

**The Credit-LLM never disburses a loan the Credit Governor would
reject, and never does so without a human sign-off.** Hard violations
(fabricated jurisdiction requirements; unsupported truth-in-lending
evidence; a disbursement attempt against an unapproved application; an
application whose own debt-to-income ratio exceeds the affordability
ceiling; a double disbursement) force **hold** and *cannot* be approved
past; a clean disbursement proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean intake-through-disbursement lifecycle + four HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a document-intake courier
robot moves physical loan-application documents, under the actor,
gated by the independent **Credit Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Credit Governor, loan-disbursement draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6492`). Related capability contracts (accounts/IBAN/double-entry-
ledger/clearing shapes) are published as [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking);
this actor's `credit.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship every prior actor has toward
its own capability lib.

## Layout

| File | Role |
|---|---|
| `src/credit/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + loan-disbursement history. No dynamically-filed sub-record -- disbursement acts directly on a pre-seeded application, the same simpler shape `casualty.store`'s/`reinsurance.store`'s `:*/bind` ops use |
| `src/credit/registry.cljc` | Loan-disbursement draft records, plus `compute-debt-to-income-ratio` (a real, simplified affordability formula) and `affordability-ceiling` (0.43, mirroring the U.S. Ability-to-Repay/Qualified Mortgage rule's general qualifying threshold) -- see docstrings for what neither models |
| `src/credit/facts.cljc` | Per-jurisdiction truth-in-lending disclosure catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/credit/creditllm.cljc` | **Credit-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/creditworthiness-screening/approval/disbursement proposals |
| `src/credit/governor.cljc` | **Credit Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · application-not-approved · affordability-exceeded, a pure ground-truth recompute needing no proposal/stored-verdict inspection) + double-disbursement guard + 1 soft (confidence/actuation gate) |
| `src/credit/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (disbursement always human; application intake is the ONLY auto-eligible op, no capital risk) |
| `src/credit/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/credit/sim.cljc` | demo driver |
| `test/credit/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers loan-application intake through underwriting approval
and disbursement -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Application intake + per-jurisdiction truth-in-lending checklisting, HARD-gated on an official spec-basis citation (`:application/intake`/`:jurisdiction/assess`) | Credit-score weighting, loan-purpose/collateral analysis, payment-history review (see `compute-debt-to-income-ratio`'s docstring) |
| Creditworthiness screening against a real, simplified debt-to-income-ratio formula (`:creditworthiness/screen`) | Real loan-servicing/banking integration, tax/regulatory reporting |
| Underwriting approval as a genuine decision, never auto-eligible even though it moves no capital (`:loan/approve`) | Collections coordination workflows |
| Loan disbursement, independently re-verified against the application's own debt-to-income ratio, with a double-disbursement guard (`:loan/disburse`) | |
| Immutable audit ledger for every intake/assessment/screening/approval/disbursement decision | |

Extending coverage is additive: add the next gate (e.g. a collections-
coordination op) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`credit.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `credit.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `credit.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Credit-LLM` + `Credit Governor` run as real, tested
code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, modeled closely on the nine prior actors'
architecture. See `docs/adr/0001-architecture.md` for the history and
design.

## License

Code and implementation templates are AGPL-3.0-or-later.
