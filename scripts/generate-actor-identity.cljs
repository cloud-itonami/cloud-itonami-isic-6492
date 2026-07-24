;; nbb script -- generates this actor's OWN outbound self-mint identity
;; (kotobase-persistence-migration, docs/adr/0003), ONCE, offline. Adapted,
;; algorithm UNCHANGED, from cloud-itonami-commitment-ledger's own script
;; of the same name (sibling actor, same fleet).
;;
;; Usage:  nbb --classpath src scripts/generate-actor-identity.cljs
;;
;; Prints the did:key and the exportable private-key material (standard
;; base64) to STDOUT. NEVER writes the private key into any committed
;; file -- the operator is expected to pipe/copy the printed value
;; straight into `wrangler pages secret put ISIC6492_ACTOR_SEED
;; --project-name cloud-itonami-isic-6492` and then discard it (this
;; script itself holds no state after the process exits).

(ns generate-actor-identity
  (:require [credit.edge.cacao-mint :as mint]))

(defn- b64url->bytes [s]
  (let [pad (case (mod (count s) 4) 2 "==" 3 "=" "")
        std (-> s (.replaceAll "-" "+") (.replaceAll "_" "/") (str pad))
        bin (js/atob std)
        n (.-length bin)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(-> (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
    (.then (fn [kp]
             (js/Promise.all #js [(js/crypto.subtle.exportKey "jwk" (.-privateKey kp))
                                   (js/crypto.subtle.exportKey "raw" (.-publicKey kp))])))
    (.then (fn [results]
             (let [jwk (aget results 0)
                   pub-raw (js/Uint8Array. (aget results 1))
                   seed-bytes (b64url->bytes (aget jwk "d"))
                   did (mint/did-key-from-raw-ed25519-pub pub-raw)
                   seed-b64 (mint/bytes->base64 seed-bytes)]
               (println "ISIC6492_ACTOR_DID=" did)
               (println "ISIC6492_ACTOR_SEED=" seed-b64)
               (println)
               (println ";; Store with e.g.:")
               (println ";;   wrangler pages secret put ISIC6492_ACTOR_SEED --project-name cloud-itonami-isic-6492")
               (println ";;   (paste the ISIC6492_ACTOR_SEED value above, then discard this terminal output)")
               (println ";; and set ISIC6492_ACTOR_DID as a plain (non-secret) Pages var --")
               (println ";; it is public information (it IS the public key, base58-encoded).")))
           )
    (.catch (fn [e]
              (js/console.error "generate-actor-identity failed:" (.-message e))
              (js/process.exit 1))))
