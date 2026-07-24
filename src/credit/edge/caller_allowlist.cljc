(ns credit.edge.caller-allowlist
  "A HARD, edge-layer-specific check: the verified CACAO's `iss` (a
  did:key) must be a member of a configured allow-list of KNOWN caller
  DIDs. This is deliberately SEPARATE from `credit.governor`'s existing
  5 checks (which this file never touches, requires, or reasons about
  business terms at all) -- it is not a business-rule gate, it is a
  transport/integration-boundary gate.

  Why this exists, and why it's not redundant with CACAO verification
  itself: a validly-signed, non-expired CACAO (`credit.edge.cacao/
  verify`, via `credit.edge.auth`) proves the SENDER genuinely controls
  the private key behind their claimed `did:key` -- it proves
  AUTHENTICITY. It says nothing about whether that authenticated sender
  is an AUTHORIZED caller of this specific HTTP integration point
  (`POST /api/loan/intake`). Any party in the world can generate a
  fresh Ed25519 keypair, self-sign a perfectly valid CACAO, and present
  it here -- CACAO validity alone would let ANY self-minted identity
  create loan applications on this actor's books. The allow-list closes
  that gap: only DIDs an operator has explicitly configured (e.g.
  `cloud-itonami-commitment-ledger`'s own self-minted actor identity,
  see that repo's `docs/adr/0003-...md`) may call this endpoint at all.
  This is defense-in-depth SPECIFIC to this cross-actor wiring, not a
  general-purpose access-control system -- isic-6492's own
  `:application/intake`/`:jurisdiction/assess`/etc. Governor checks are
  entirely unmodified and unaware this file exists.

  Configuration: a comma-separated list of did:key strings, read from
  `env.KNOWN_CALLER_DIDS` (a Cloudflare Pages secret or plain var --
  operator's choice; see `wrangler.jsonc` and this repo's own `docs/
  adr/0002-http-edge-loan-intake.md`). Pure/portable -- no `js/`
  interop, directly unit-testable under `clojure -M:dev:test`."
  (:require [clojure.string :as str]
            [credit.edge.auth :as auth]))

(defn parse-allowlist
  "The raw comma-separated `KNOWN_CALLER_DIDS` env-var string (or nil/
  blank, which yields an EMPTY set -- fail-closed: no configured caller
  DIDs means no caller is authorized, not 'allow everyone') -> a set of
  trimmed, non-blank did:key strings."
  [raw]
  (if (string? raw)
    (into #{} (comp (map str/trim) (remove str/blank?)) (str/split raw #","))
    #{}))

(defn allowed?
  [allowlist iss]
  (boolean (and iss (contains? (or allowlist #{}) iss))))

(defn check
  "allowlist (a set, from `parse-allowlist`), iss (the CACAO-verified
  caller DID) -> {:ok? bool :response map|nil} -- the same short-
  circuit shape `credit.edge.auth/verify-cacao-header` and
  `commitledger.edge.auth/require-lender` already use."
  [allowlist iss]
  (if (allowed? allowlist iss)
    {:ok? true :response nil}
    {:ok? false
     :response (auth/json-response
                {:ok false :error "forbidden"
                 :reason "caller DID is not on this integration point's allow-list (KNOWN_CALLER_DIDS)"}
                403)}))
