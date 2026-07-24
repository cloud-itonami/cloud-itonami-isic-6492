(ns credit.edge.kotobase-identity
  "Self-mint CACAO identity for THIS actor's calls to kotobase.net
  (kotobase-persistence-migration, docs/adr/0003-kotobase-persistence-
  migration.md). isic-6492 had NO self-mint identity before this
  migration (V3 only gave it inbound CACAO verify + a caller allow-list
  -- `credit.edge.cacao`/`credit.edge.caller-allowlist`); this ns is the
  FIRST outbound identity this actor mints for itself, generated ONCE,
  offline, via `scripts/generate-actor-identity.cljs` (an adapted,
  algorithm-unchanged copy of `cloud-itonami-commitment-ledger`'s own
  script of the same name) and stored as the `ISIC6492_ACTOR_SEED`
  Cloudflare Pages secret + `ISIC6492_ACTOR_DID` public var on THIS
  actor's own Cloudflare Pages project.

  Resource scope / aud / domain / mint-freshness discipline all mirror
  `commitledger.edge.kotobase-identity` (sibling actor, same fleet, same
  migration) precisely -- see that ns's docstring for the full
  provenance chain AND for the 3 confirmed-live bugfixes both actors
  needed (missing `:exp`, missing envelope `s.t`/non-canonical CBOR key
  order, and the resource-scope shape itself: `:graph` MUST be this
  actor's own did:key, not a computed CID -- `kotoba-lang/kotobase-
  client`'s `kotobase.cacao.cljs` docstring confirms this for the
  'apex'/kotobase.net profile, 'live-probed 2026-07-09'). Only the
  seed/DID source env vars differ (`ISIC6492_ACTOR_SEED`/`_DID` vs
  `COMMITMENT_LEDGER_ACTOR_SEED`/`_DID`) and the JWK-signing-key import
  is INLINED here (`import-signing-key` below) rather than borrowed
  from a sibling outbound-client ns, since isic-6492 has no pre-existing
  outbound client ns to host it the way commitment-ledger's
  `isic6492-client` does -- same algorithm, same empirical finding (JWK
  reconstruction, not raw-format PRIVATE key import; see that fn's own
  docstring).

  CLJS-only (js/crypto.subtle) -- like `commitledger.edge.kotobase-
  identity`, this ns's own JVM test is a documented no-op stub
  (`commitledger.edge.cacao-mint-test`'s established precedent); the
  real round-trip was verified live against production, 2026-07-24 (see
  docs/adr/0003).

  NOTE the `:require` clause below is itself `:cljs`-only (see
  `commitledger.edge.kotobase-identity`'s own docstring for why a
  `:require` clause with zero specs is rejected by `clojure.core.specs.
  alpha/ns-form`, and why a whole conditional `(:require ...)` clause
  with no `:clj` branch is the fix)."
  #?(:cljs (:require [credit.edge.base58 :as base58]
                     [credit.edge.cacao :as cacao]
                     [credit.edge.cacao-mint :as mint]
                     [credit.edge.cbor :as cbor])))

(def default-kotobase-aud
  "net-kotobase's pod enforces aud == did:web:kotobase.net."
  "did:web:kotobase.net")

(def default-kotobase-domain "kotobase.net")

(def default-db-name
  "This actor's kotobase.net tenant database name -- the credit
  application/ledger/disbursement history."
  "isic-6492-credit")

(defn kotobase-resources
  "CACAO resource scope for a kotobase.net graph read/write.

  BUGFIX (kotobase-persistence-migration, docs/adr/0003): `:graph`
  resource MUST be this actor's OWN did:key -- NOT a computed CID (this
  ns's earlier draft derived one the way `crm.kotobase/canonical-graph`,
  ADR-2607184000, does) and NOT `db-name`. Confirmed directly against
  production, 2026-07-24 (see `commitledger.edge.kotobase-identity/
  kotobase-resources`'s own docstring -- sibling actor, same fleet,
  same fix, found first there): a write scoped to a computed graph CID
  consistently 401'd; the SAME write scoped to `(str \"kotoba://graph/\"
  did)` succeeded. `did` is this actor's own did:key (the CACAO's
  `iss`); `op` is \"datom:read\" or \"datom:transact\" (callers mint a
  SEPARATE CACAO per direction)."
  [did op]
  ["kotoba://can/kotobase:pin"
   (str "kotoba://can/" op)
   (str "kotoba://graph/" did)])

;; ───────── seed / identity ─────────────────────────────────────────────────

#?(:cljs
   (defn- std-b64->b64url [s]
     (-> s (.replace "+" "-") (.replace "/" "_") (.replace "=" ""))))

#?(:cljs
   (defn import-signing-key
     "seed-b64 (standard base64, the 32-byte raw Ed25519 private seed
     `scripts/generate-actor-identity.cljs` printed and
     `ISIC6492_ACTOR_SEED` stores), did (this actor's own did:key,
     `ISIC6492_ACTOR_DID`) -> promise-like of a CryptoKey usable for
     `js/crypto.subtle.sign`. Same JWK-reconstruction algorithm as
     `commitledger.edge.isic6492-client/import-signing-key` (see that
     fn's docstring for the full empirical finding: raw-format PRIVATE
     key import is unsupported for Ed25519 on this runtime family; JWK
     import works when both 'd' and 'x' are present, 'x' recoverable
     from `did` alone)."
     [seed-b64 did]
     (let [seed-bytes (cacao/base64->bytes seed-b64)
           pub-bytes (.slice (base58/decode (subs did (count "did:key:z"))) 2)
           d (std-b64->b64url (mint/bytes->base64 seed-bytes))
           x (std-b64->b64url (mint/bytes->base64 pub-bytes))
           jwk #js {:kty "OKP" :crv "Ed25519" :d d :x x}]
       (.importKey js/crypto.subtle "jwk" jwk #js {:name "Ed25519"} false #js ["sign"]))))

#?(:cljs
   (defn signing-key-from-env
     "env -> promise-like of `{:did :sign-fn}`, or nil if `$ISIC6492_
     ACTOR_SEED`/`$ISIC6492_ACTOR_DID` are unset (fail-soft, same
     convention as `commitledger.edge.isic6492-client/live-client-from-
     env`)."
     [env]
     (let [seed-b64 (aget env "ISIC6492_ACTOR_SEED")
           did (aget env "ISIC6492_ACTOR_DID")]
       (if (or (not seed-b64) (not did))
         (js/Promise.resolve nil)
         (.then (import-signing-key seed-b64 did)
                (fn [priv-key]
                  {:did did
                   :sign-fn (fn [msg-bytes] (.sign js/crypto.subtle "Ed25519" priv-key msg-bytes))}))))))

;; ───────── kotobase.net-specific CBOR envelope (see commitledger.edge.
;; kotobase-identity's own docstring for the full bugfix explanation --
;; net-kotobase's edge auth gate requires the signature sub-map to carry
;; "t": "EdDSA", which credit.edge.cacao-mint/mint + credit.edge.cbor/
;; encode-cacao-envelope never included (built for, and still correct
;; for, credit.edge.cacao/verify -- this repo's own, more lenient
;; verifier, which never reads s.t). Also encodes the "p" map's keys in
;; DAG-CBOR canonical order.) ───────────────────────────────────────────

#?(:cljs
   (defn- iso8601-seconds
     "epoch-ms -> strict `YYYY-MM-DDTHH:MM:SSZ` (no fractional seconds)."
     [epoch-ms]
     (.replace (.toISOString (js/Date. epoch-ms)) #"\.\d{3}Z$" "Z")))

#?(:cljs
   (defn- canonical-sort-pairs
     "`[[k v] ...]` -> sorted by DAG-CBOR core deterministic key order
     (byte-length, then bytewise-lexicographic)."
     [pairs]
     (sort-by (fn [[k _]] [(count k) k]) pairs)))

#?(:cljs
   (defn- encode-kotobase-cacao-envelope
     "`{\"h\": {\"t\": \"caip122\"}, \"p\": <p-pairs, CANONICALLY sorted>,
     \"s\": {\"t\": \"EdDSA\", \"s\": sig-b64}}`, as a plain Clojure
     vector of byte ints."
     [p-pairs sig-b64]
     (into (cbor/header 5 3)
           (concat (cbor/encode-text "h") (cbor/encode-map [["t" "caip122"]])
                   (cbor/encode-text "p") (cbor/encode-map (canonical-sort-pairs p-pairs))
                   (cbor/encode-text "s") (cbor/encode-map (canonical-sort-pairs [["t" "EdDSA"] ["s" sig-b64]]))))))

#?(:cljs
   (defn mint-kotobase-cacao!
     "`{:did :sign-fn}` (from `signing-key-from-env`), `op` (\"datom:read\"
     or \"datom:transact\") -> promise-like of a base64 CACAO string. A
     FRESH CACAO every call -- never cache/reuse one across requests
     (kotobase-server's nonce-replay protection 401s a second
     `datomic.transact` presenting the same CACAO's nonce, confirmed
     against production, ADR-2607184000).

     Builds the SIWE plaintext via `credit.edge.cacao/siwe-message` and
     signs it directly with `sign-fn`, then assembles the envelope via
     THIS ns's own `encode-kotobase-cacao-envelope` (NOT `credit.edge.
     cacao-mint/mint`'s -- see ns docstring for why: the shared one is
     missing kotobase.net's required `s.t` field)."
     [{:keys [did sign-fn]} op]
     (let [now-ms (js/Date.now)
           ttl-sec 3600
           iat (iso8601-seconds now-ms)
           exp (iso8601-seconds (+ now-ms (* ttl-sec 1000)))
           nonce (str (js/Math.floor (* (js/Math.random) 1e12)))
           resources (kotobase-resources did op)
           payload #js {:iss did :aud default-kotobase-aud :iat iat :exp exp
                        :nonce nonce :domain default-kotobase-domain :version "1"
                        :resources (clj->js resources)}
           msg (cacao/siwe-message payload)
           msg-bytes (.encode (js/TextEncoder.) msg)]
       (.then (sign-fn msg-bytes)
              (fn [sig-ab]
                (let [sig-b64 (mint/bytes->base64 (js/Uint8Array. sig-ab))
                      p-pairs [["iss" did] ["aud" default-kotobase-aud] ["iat" iat] ["exp" exp]
                               ["nonce" nonce] ["domain" default-kotobase-domain] ["version" "1"]
                               ["resources" resources]]
                      envelope-bytes (encode-kotobase-cacao-envelope p-pairs sig-b64)]
                  (mint/bytes->base64 (js/Uint8Array.from (clj->js envelope-bytes)))))))))
