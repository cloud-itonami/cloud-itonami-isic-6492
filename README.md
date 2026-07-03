# cloud-itonami-6492

Open Business Blueprint for **ISIC Rev.5 6492**: Other credit granting.

This repository designs a forkable OSS business for other credit granting -- consumer/commercial lending outside deposit-taking banking (installment credit, pawnbroking, personal lending) -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-intake courier robot moves physical loan-application documents,
under an actor that proposes actions and an independent **Credit Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/account records
        |
        v
Credit-LLM -> Credit Governor -> hold, proceed, or human approval
        |
        v
case/account ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: disbursing loan funds.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6492`). Required capabilities are implemented by:

- [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking)
  -- accounts, IBAN, double-entry ledger, clearing

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`Credit-LLM` + `Credit Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
