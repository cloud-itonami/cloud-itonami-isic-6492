# Business Model: Other credit granting

## Classification

- Repository: `cloud-itonami-isic-6492`
- ISIC Rev.5: `6492`
- Activity: other credit granting -- consumer/commercial lending outside deposit-taking banking (installment credit, pawnbroking, personal lending)
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent consumer-lending operators
- cooperative credit programs
- community microcredit operators

## Offer

- loan-application intake
- creditworthiness disclosure proposal
- loan-disbursement proposal
- collections coordination
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per loan-in-force
- support: monthly retainer with SLA
- migration: import from an incumbent lending system
- disbursement fee

## Trust Controls

- no loan is disbursed without human sign-off
- a fabricated jurisdiction truth-in-lending citation, incomplete
  disclosure evidence, a disbursement attempt against an application
  that was never approved, or an application whose own debt-to-income
  ratio exceeds the affordability ceiling -- each forces a hold, not an
  override
- a loan cannot be disbursed twice: a double-disbursement attempt is
  held off this actor's own application status alone, with no upstream
  comparison needed
- every intake, assessment, screening, approval and disbursement path
  is auditable
- emergency manual override paths remain outside LLM control
