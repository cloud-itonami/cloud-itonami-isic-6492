# Operator Quickstart

## Who This Is For

This blueprint is for **licensed lending institutions, fintech operators, and open-business partners** who want to:
- Deploy a governed credit-granting system without rebuilding compliance from scratch
- Fork this blueprint and operate it in your own jurisdiction with your own license
- Integrate an LLM-powered intake/screening pipeline that cannot disburse without human approval
- Run a complete audit trail from application through disbursement

If you're not a licensed lender, you can still fork and study the architecture; the Credit Governor pattern applies to any high-stakes decision workflow.

## Prerequisites

- **Clojure** — [install](https://clojure.org/guides/getting_started)
- **Git** — to clone and fork this repository

### For workspace development (optional)
If running inside the monorepo checkout (`orgs/cloud-itonami/cloud-itonami-isic-6492/`), dependencies resolve from local checkouts:
- [`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph) — StateGraph runtime (`:local/root ../../kotoba-lang/langgraph`)
- [`kotoba-lang/langchain`](https://github.com/kotoba-lang/langchain) — LLM tooling (dev-only dependency)

For a standalone fork, update `deps.edn` to use git coordinates instead of `:local/root`.

## Run the demo

Walk one complete loan-application lifecycle (intake → assessment → screening → approval → disbursement) and four hard-hold cases:

```bash
clojure -M:dev:run
```

This invokes the demo driver (`src/credit/sim.cljc`), which seeds a test application and runs it through the OperationActor under the Credit Governor. Output logs each phase transition and governor decision.

## Run tests

Execute the full test suite (governor contract, phase invariants, store parity, registry conformance, facts coverage):

```bash
clojure -M:dev:test
```

This uses Cognitect's test runner and executes all tests in `test/credit/`.

### ClojureScript portable tests (primary gate)
The core actor code is written in `.cljc` (portable Clojure) and must pass under ClojureScript as well:

```bash
clojure -Sdeps '{:paths ["src" "test"]}' -M:dev:cljs \
  -m cljs.main --target node -m credit.portable-cljs-test-runner
```

## Lint

Static analysis with clj-kondo:

```bash
clojure -M:lint
```

## Core modules

### Credit Governor
**Location:** [`src/credit/governor.cljc`](../src/credit/governor.cljc)

The independent governance layer that enforces four hard-stop checks before any loan action:
1. **spec-basis** — jurisdiction truth-in-lending requirement must cite an official source
2. **evidence-incomplete** — disclosure evidence must match the requirement's spec
3. **application-not-approved** — disbursement forbidden on unapproved applications
4. **affordability-exceeded** — debt-to-income ratio recomputed fresh from the application; exceeding 0.43 (U.S. Qualified Mortgage ceiling) forces a hold

The governor also prevents double-disbursement by checking the application's own status, and gates high-stakes actions (`:loan/disburse`) to human-only execution. Fabricated citations, incomplete evidence, or affordability violations force a hard hold with no override—audit every decision in [`src/credit/store.cljc`](../src/credit/store.cljc).

### Phase State Machine
**Location:** `src/credit/phase.cljc`

Defines four phases:
- **Phase 0:** read-only — application data locked after intake
- **Phase 1:** assisted intake — forms and documentation collection
- **Phase 2:** assisted assessment — creditworthiness analysis (LLM)
- **Phase 3:** supervised — disbursement decision (human-only)

Only application intake (`:application/intake`) is auto-eligible; all other high-stakes operations require human sign-off.

### Store & Audit Ledger
**Location:** `src/credit/store.cljc`

Pluggable store protocol supporting in-memory (`MemStore`) or Datomic (`DatomicStore`) backends. All operations append to an immutable audit ledger; disbursement history is separate from the application record.

### Loan Registry
**Location:** `src/credit/registry.cljc`

- `compute-debt-to-income-ratio` — simplified affordability formula (total debt / gross income)
- `affordability-ceiling` — 0.43, mirroring U.S. Ability-to-Repay rule
- Loan disbursement draft records

See the module docstring for what this simplified formula does *not* model (credit scores, collateral analysis, payment history).

### Jurisdiction Facts Catalog
**Location:** `src/credit/facts.cljc`

Truth-in-lending disclosure requirements by jurisdiction, each citing an official spec-basis source. Currently seeded with 4 jurisdictions (JPN, USA, GBR, DEU). Adding a jurisdiction is additive: one map entry with a real official citation, never fabricated.

### OperationActor
**Location:** `src/credit/operation.cljc`

The langgraph-clj StateGraph that orchestrates intake → assessment → screening → approval → disbursement with supervised checkpoints and escalation to human review.

## Deployment checklist

1. **Register operator license and jurisdiction** — supply proof of lending authority in your jurisdiction
2. **Configure Credit Governor policy** — set affordability ceiling, disclosure requirements and escalation rules
3. **Import historical accounts** (if migrating) — validate existing records against this blueprint's contracts
4. **Dry-run the first application** — verify audit export, governor decisions, and audit trail
5. **Enable production mode** — turn on disbursement actuation and backup manual override procedures

See [`docs/operator-guide.md`](./operator-guide.md) for minimum production controls.
