# ADR-0001: cloud-itonami-isic-6492 -- Credit-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612` ADR-0001s (the pattern this ADR ports; `6512`'s/
  `6530`'s/`6820`'s ADRs establish the "write the lesson down, don't
  just fix it" discipline -- this build both reuses a lesson correctly
  AND, despite that, rediscovers a variant of an EARLIER lesson the
  hard way, see Decision 5); ADR-2607071250 (`cloud-itonami-isic-6612`,
  the first vertical built outside ADR-2607032000's original batch --
  this is the second)
- Context: Continuing the standing "pick a new ISIC blueprint vertical"
  direction past ADR-2607032000's original batch (closed) and past
  `6612` (the first post-batch build), this ADR deepens `cloud-itonami-
  isic-6492` (other credit granting) from `:blueprint` to
  `:implemented`, the tenth actor in this fleet -- the first LENDING
  vertical.

## Problem

Consumer/commercial credit granting bundles several distinct concerns
under one governed workflow:

1. **Jurisdiction truth-in-lending disclosure correctness** -- is the
   required evidence for disbursing a loan based on an official
   regulator, or invented?
2. **Affordability correctness** -- does an applicant's own debt-to-
   income ratio actually exceed a responsible-lending ceiling? Unlike
   every other arithmetic check in this fleet (which compare a
   recompute against something a proposal CLAIMED), this check's
   inputs are permanent ground-truth fields already on the application
   -- there is no separate claimed figure to re-derive against.
3. **Underwriting-approval correctness** -- has an application actually
   been approved before disbursement? A genuinely new status-lifecycle
   shape: unlike every prior "not-bound"-style check in this fleet,
   THIS status legitimately advances PAST the checked value after
   actuation (see Decision 5 -- a real bug, found and fixed).
4. **A single real actuation event** -- disbursing real loan funds,
   with no second actuation event to manufacture for symmetry.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run consumer lending with an LLM" but "seal
the LLM inside a trust boundary and layer evidence-sufficiency,
affordability correctness, approval-lifecycle correctness, audit and
human-approval on top of it, while structurally fixing the one real
actuation event as human-only."

## Decision

### 1. Credit-LLM is sealed into the bottom node; it never disburses directly

`credit.creditllm` returns exactly five kinds of proposal: intake
normalization, jurisdiction truth-in-lending checklist,
creditworthiness/affordability screening, loan-approval draft, and
loan-disbursement draft. No proposal writes the SSoT or commits a real
loan disbursement directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 credit-granting operation

`credit.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. No dynamically-filed sub-record -- disbursement acts directly on a pre-seeded application

Unlike `pension.store`/`realty.store`/`brokerage.store` (each with a
sub-record -- a disbursement, a fee request, an order -- distinct from
the entity it's filed against), `credit.store` has only ONE entity:
the loan application itself. This mirrors `casualty.store`'s/
`reinsurance.store`'s simpler `:policy/mark-bound`/`:treaty/mark-bound`
shape, where there is no "entity is missing" failure mode to guard
against. Consequently `spec-basis-violations` needs NO proactive
existence-guard here, unlike `pension.governor`'s/`realty.governor`'s/
`brokerage.governor`'s (each of which shares an op with a `:*-missing`
check) -- an honest reflection of this domain's simpler entity shape,
not an oversight.

### 4. `affordability-exceeded-violations` is a pure ground-truth recompute, needing NO proposal inspection or stored-verdict lookup

`credit.registry/compute-debt-to-income-ratio`'s inputs (`:existing-
debt`/`:requested-amount`/`:annual-income`) are permanent facts already
persisted on the application -- there is no separate claimed figure to
compare against (contrast the exact-match checks in `6629`/`6520`/
`6820`/`6612`, which compare a recompute against a proposal's CLAIM).
The check therefore recomputes straight from the application every time
it applies, for BOTH `:creditworthiness/screen` and `:loan/disburse` --
simpler than every prior unconditional-evaluation check in this fleet
(`casualty.governor/sanctions-violations`'s three reuses, `brokerage.
governor/suitability-failure-violations`), which all needed to inspect
either an in-flight proposal or a stored screening verdict. This is
this build's principal structural contribution: when a threshold
check's inputs are permanent entity fields, the governor doesn't need
a stored-verdict step at all.

### 5. A REAL bug WAS caught during demo verification -- a variant of `6622`'s status-lifecycle trap, rediscovered despite documenting it four times prior

`application-not-approved-violations` initially checked `(= :approved
(:status application))` directly, reasoning (incorrectly, by direct
analogy to `6520`'s/`6530`'s/`6820`'s/`6612`'s SAFE reuses) that the
application's status never regresses out of `:approved`. But
`:loan/disburse`'s own commit ADVANCES status from `:approved` to
`:disbursed` -- the SAME shape `cloud-itonami-isic-6622`'s original
`placement-not-bound-violations` bug had. The demo's double-
disbursement scenario showed `[:evidence-incomplete... wait
:application-not-approved :double-disbursement]` co-firing on the
SECOND disbursement attempt: after the first successful disbursement,
status is `:disbursed`, not `:approved`, so the direct-equality check
spuriously re-fired. Fixed the same way `6622` fixed it: check for a
status VALUE SET covering every state reachable only via approval
(`#{:approved :disbursed}` -- `:disbursed` is only reachable by first
passing this very check), not a single terminal value. **This is a
sobering data point**: having written down "check presence of a fact
set once and never cleared, not the current status value" as an
explicit lesson in FOUR prior ADRs (`6520`, `6530`, `6820`, `6612`) did
not prevent re-deriving the WRONG conclusion here by pattern-matching
on the safe cases' shape rather than actually verifying whether THIS
domain's status advances past the checked value. The lesson is now
restated more precisely: before reusing "status never regresses,
check it directly," explicitly trace what the ACTUATION op's own
commit does to status -- don't assume by analogy to prior safe cases.

### 6. Single actuation event

`credit.governor`'s `high-stakes` set has exactly ONE member
(`:actuation/disburse-loan`), matching `6511`'s/`6621`'s/`6629`'s/
`6612`'s single-actuation shape -- this domain genuinely has one
real-world financial act.

### 7. Underwriting approval is never auto-eligible, even though it moves no capital

`:loan/approve` is a genuine underwriting DECISION (not mere data
normalization), so it is treated like every sibling's jurisdiction-
assessment op: always escalate, never auto, even at phase 3. Phase 3's
`:auto` set here has only ONE member (`:application/intake`) -- fewer
than every prior actor's two-member set, since this domain has no
separate no-capital-risk "file" lifecycle distinct from the
application itself.

### 8. No fabricated international loan-disbursement-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a loan-disbursement reference
number. `credit.registry` therefore does not invent one; it validates
required fields and assigns a jurisdiction-scoped sequence number only.

### 9. Relationship to `kotoba-lang/banking`

This fleet's FIRST actor whose capability lib is `kotoba-lang/banking`
(accounts/IBAN/double-entry-ledger/clearing contracts) -- but the same
self-contained-sibling posture holds: no code dependency.

## Consequences

- (+) Consumer/commercial credit granting gets the same governed,
  auditable-actor treatment as the nine prior actors -- any licensed
  lender can fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/credit/phase_test.clj`'s `loan-disburse-
  never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/credit/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) `affordability-exceeded-violations` demonstrates a genuinely
  simpler check shape (pure ground-truth recompute, no proposal/
  stored-verdict dependency) than every prior unconditional-evaluation
  check in this fleet.
- (+) The status-lifecycle bug (Decision 5) is now regression-tested by
  `test/credit/governor_contract_test.clj`'s `loan-disburse-double-
  disbursement-is-held`, and the lesson is restated more precisely for
  future reuse: verify what the actuation op's own commit does to
  status, don't pattern-match on prior safe cases by analogy.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `credit.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `compute-debt-to-income-ratio` models only a single debt-to-
  income ratio check, not a full underwriting model (credit-score
  weighting, loan-purpose/collateral analysis, payment-history review
  are out of scope -- see that fn's own docstring); real loan-
  servicing/banking integration, collections coordination, and tax/
  regulatory reporting are all out of scope for this OSS actor -- each
  operator's responsibility (see README's coverage table).
- 31 tests / 128 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6492` at `:blueprint` only | ❌ | The standing "pick a new ISIC blueprint vertical" direction continues past `6612`; this is the natural next lending-adjacent vertical |
| Add a separate boolean `:approved?` field instead of checking a status-value set | ❌ | The status-value-set fix (`#{:approved :disbursed}`) requires no schema change and is provably sufficient, since `:disbursed` is only reachable by first passing the approval check -- a separate field would be redundant bookkeeping for the same fact |
| Model a full underwriting analysis (credit score, collateral, payment history) for conformance-test rigor | ❌ | Genuinely more complex real-world underwriting that this R0 does not claim to model correctly -- honestly scoped to a single debt-to-income-ratio check instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Require `kotoba.banking` (the capability lib) directly from `credit.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
