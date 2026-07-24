(ns credit.edge.cacao-mint
  "CAIP-122/SIWE CACAO mint for the edge -- a direct, faithful PORT of
  `cloud-itonami-commitment-ledger`'s own `commitledger.edge.cacao-mint`
  (itself a port of `gftdcojp/cloud-itonami`'s `cloud_itonami.edge.
  cacao-mint`, AGPL-3.0-or-later) -- see `credit.edge.base58`'s ns
  docstring for the full local-mirror-not-cross-repo-require rationale.
  Algorithm UNCHANGED from every prior copy -- do not reinvent the wire
  format.

  This is this actor's FIRST self-mint outbound identity
  (kotobase-persistence-migration, docs/adr/0003) -- V3 gave isic-6492
  inbound CACAO verify (`credit.edge.cacao`) + a caller allow-list
  (`credit.edge.caller-allowlist`) but no outbound self-mint, since this
  actor previously made no outbound CACAO-gated calls of its own. This
  migration's Store (`credit.edge.kotobase-store`) is the first thing
  that needs one, to authenticate this actor's OWN writes/reads to its
  own kotobase.net graph. The identity is generated ONCE, offline, by
  `scripts/generate-actor-identity.cljs` (adapted, unchanged algorithm,
  from `cloud-itonami-commitment-ledger`'s own script of the same name)
  and stored as the `ISIC6492_ACTOR_SEED` Cloudflare Pages secret +
  `ISIC6492_ACTOR_DID` public var; at request time `credit.edge.
  kotobase-identity` imports it the same way `commitledger.edge.
  isic6492-client/import-signing-key` does (a JWK reconstruction, not
  raw-format PRIVATE key import -- see that ns's docstring for the full
  empirical finding this port inherits unchanged).

  CLJS-only (js/crypto.subtle, js/Promise, js/btoa)."
  (:require [credit.edge.base58 :as base58]
            [credit.edge.cbor :as cbor]
            [credit.edge.cacao :as cacao]))

(defn did-key-from-raw-ed25519-pub
  "did:key:z... (Ed25519, multicodec 0xed01) from a raw 32-byte public
  key -- the mint-side inverse of `cacao.cljc`'s private `did-key-
  >pubkey`."
  [raw-pub-bytes]
  (str "did:key:z" (base58/encode (js/Uint8Array.from
                                   (into [0xed 0x01] (array-seq (js/Array.from raw-pub-bytes)))))))

(defn bytes->base64 [bytes]
  (let [arr (js/Array.from bytes)]
    (js/btoa (apply str (map js/String.fromCharCode (array-seq arr))))))

(defn mint
  "Sign `fields` (:domain :aud :version :nonce :iat :exp :resources --
  all strings except :resources, a vector-of-strings or nil) as `iss`
  using `sign-fn` (a fn of msg-bytes -> Promise<sig-bytes>, e.g.
  `#(js/crypto.subtle.sign \"Ed25519\" priv-key %)`), and assemble the
  base64 CACAO blob `cacao/verify` accepts unmodified. Returns a
  Promise<{:cacao-b64 :iss}>."
  [iss sign-fn fields]
  (let [payload #js {:iss iss
                      :aud (:aud fields)
                      :iat (:iat fields)
                      :exp (:exp fields)
                      :nonce (:nonce fields)
                      :domain (:domain fields)
                      :version (or (:version fields) "1")
                      :resources (clj->js (or (:resources fields) []))}
        msg (cacao/siwe-message payload)
        msg-bytes (.encode (js/TextEncoder.) msg)]
    (-> (sign-fn msg-bytes)
        (.then
         (fn [sig-ab]
           (let [sig-b64 (bytes->base64 (js/Uint8Array. sig-ab))
                 p-pairs (cond-> [["iss" iss]
                                  ["aud" (:aud fields)]
                                  ["iat" (:iat fields)]
                                  ["nonce" (:nonce fields)]
                                  ["domain" (:domain fields)]
                                  ["version" (or (:version fields) "1")]]
                           (:exp fields) (conj ["exp" (:exp fields)])
                           (seq (:resources fields)) (conj ["resources" (vec (:resources fields))]))
                 outer (cbor/encode-cacao-envelope p-pairs sig-b64)]
             {:cacao-b64 (bytes->base64 (js/Uint8Array.from (clj->js outer)))
              :iss iss}))))))
