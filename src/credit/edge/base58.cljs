(ns credit.edge.base58
  "base58btc (Bitcoin alphabet) decode+encode for edge did:key parsing --
  a direct, faithful PORT of `cloud-itonami-commitment-ledger`'s own
  `commitledger.edge.base58` (`orgs/cloud-itonami/cloud-itonami-
  commitment-ledger`, AGPL-3.0-or-later, same license as this repo),
  which is itself a faithful port of `cloud_itonami.edge.base58`
  (`orgs/gftdcojp/cloud-itonami`) -- this actor mirrors the SAME
  local-mirror-not-cross-repo-require convention that repo's own
  `docs/adr/0002-http-edge-live-registry-verification.md` Decision 3
  documents (this workspace's established convention for actor repos:
  shared wire format, no shared code across the repo boundary). The
  algorithm itself is UNCHANGED across all three copies -- do not
  reinvent the wire format.

  CLJS-only (like every prior copy: `js/` interop throughout, no JVM
  branch -- this runs in the Cloudflare Workers isolate, not the JVM)."
  )

(def ^:private alphabet "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(def ^:private char->index (into {} (map-indexed (fn [i c] [c i]) alphabet)))

(defn decode
  "base58btc string -> js/Uint8Array."
  [s]
  (let [out (array)]
    (doseq [c s]
      (let [v (get char->index c)]
        (when (nil? v)
          (throw (js/Error. (str "bad base58 char: " c))))
        (let [carry (atom v)
              n (aget out "length")]
          (loop [i (dec n)]
            (when (>= i 0)
              (let [total (+ @carry (* 58 (aget out i)))]
                (aset out i (bit-and total 0xff))
                (reset! carry (bit-shift-right total 8)))
              (recur (dec i))))
          (loop []
            (when (pos? @carry)
              (.unshift out (bit-and @carry 0xff))
              (reset! carry (bit-shift-right @carry 8))
              (recur))))))
    (let [leading (count (take-while #(= % \1) s))]
      (js/Uint8Array.from (clj->js (concat (repeat leading 0) (array-seq out)))))))

(defn encode
  "byte sequence (js/Uint8Array or any seqable of 0-255 ints) -> base58btc
  string. The exact inverse of `decode` above."
  [bytes]
  (let [bytes (vec (array-seq (js/Array.from bytes)))
        digits (atom [0])]
    (doseq [b bytes]
      (let [carry (atom b)]
        (dotimes [i (count @digits)]
          (let [v (+ @carry (* (nth @digits i) 256))]
            (swap! digits assoc i (mod v 58))
            (reset! carry (quot v 58))))
        (while (pos? @carry)
          (swap! digits conj (mod @carry 58))
          (reset! carry (quot @carry 58)))))
    (let [leading (count (take-while zero? bytes))
          all-zero? (every? zero? bytes)
          body (if all-zero? "" (apply str (map #(nth alphabet %) (reverse @digits))))]
      (str (apply str (repeat leading (first alphabet))) body))))
