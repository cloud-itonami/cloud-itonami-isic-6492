(ns credit.edge.pcompat
  "Minimal portable async seam -- a direct, faithful MIRROR of
  `cloud-itonami-commitment-ledger`'s `commitledger.edge.pcompat` (same
  fleet, same convention: this actor's edge layer is new, so it copies
  the pattern rather than re-inventing it). `:clj` `then` calls `f` on
  `p` directly (JVM tests use synchronous Mock/Mem values, no event
  loop); `:cljs` `then` chains a real `js/Promise`. Every `credit.edge.*`
  core-logic fn threads exclusively through `resolved`/`then` here, so
  the SAME function body runs unchanged on both platforms."
  )

(defn resolved
  [v]
  #?(:cljs (js/Promise.resolve v)
     :clj  v))

(defn then
  [p f]
  #?(:cljs (.then p f)
     :clj  (f p)))
