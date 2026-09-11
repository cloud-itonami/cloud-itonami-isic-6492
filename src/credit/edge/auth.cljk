(ns credit.edge.auth
  "Portable CACAO header verification for isic-6492's edge layer --
  mirrors `cloud-itonami-commitment-ledger`'s own `commitledger.edge.
  auth` (same swap-not-rewrite `CacaoVerifier` injection-seam
  philosophy: a protocol + `mock-verifier` for JVM tests + a `:cljs`-
  only `live-verifier` wired to the real crypto, so this ns has ZERO
  `js/` interop of its own and is directly testable under `clojure
  -M:dev:test`), MINUS that ns's resource-scope / lender-identity
  checks. isic-6492's own caller-authorization gate is a SEPARATE,
  second-stage check, `credit.edge.caller-allowlist` (a DID allow-list,
  not a resource-scope claim) -- see that ns's docstring for why the
  shape differs: this endpoint's caller is another ACTOR in this fleet
  (e.g. `cloud-itonami-commitment-ledger`'s own self-minted identity),
  not a borrower claiming ownership of a tenant, so there is no
  `kotoba://itonami/{org}/{repo}` resource scope to check here -- only
  'is this specific caller DID one this integration point trusts'.

  The REAL crypto (`credit.edge.cacao/verify`, a faithful port of
  `commitledger.edge.cacao`, itself a port of `cloud_itonami.edge.
  cacao`) is NOT re-tested here -- same reasoning as every prior copy
  in this fleet (see `credit.edge.cacao`'s ns docstring): it is CLJS-
  only real Ed25519 crypto with no JVM equivalent to port to without
  reinventing the wire format."
  (:require [credit.edge.pcompat :as pc]
            #?@(:cljs [[credit.edge.cacao :as cacao]]
                :clj  [])))

(defprotocol CacaoVerifier
  (-verify-cacao [v cacao-b64]
    "cacao-b64 -> promise-like of {:valid? bool :iss str|nil :error
    str|nil}. Never throws -- mirrors `commitledger.edge.auth`'s
    `CacaoVerifier` contract exactly, minus the `:resources` field
    (unused here -- see ns docstring)."))

(defrecord MockCacaoVerifier [result-fn]
  CacaoVerifier
  (-verify-cacao [_ cacao-b64] (pc/resolved (result-fn cacao-b64))))

(defn mock-verifier
  [result-fn]
  (->MockCacaoVerifier result-fn))

(defn always-valid-verifier
  "A MockCacaoVerifier that always succeeds for `iss` -- the common
  positive-path test fixture."
  [iss]
  (mock-verifier (fn [_] {:valid? true :iss iss})))

(def ^:private cacao-header-re #"(?i)^CACAO\s+(.+)$")

(defn json-response
  [body status]
  {:status status :body body})

(defn verify-cacao-header
  "verifier, cacao-header (the raw `Authorization` header string, or
  nil) -> promise-like of {:ok? bool :iss str|nil :response map|nil}.
  Signature-and-temporal verification ONLY -- no resource-scope check
  (see ns docstring: that is `credit.edge.caller-allowlist`'s job, a
  separate, second-stage gate)."
  [verifier cacao-header]
  (let [m (when cacao-header (re-matches cacao-header-re cacao-header))]
    (if-not m
      (pc/resolved {:ok? false :iss nil
                    :response (json-response {:ok false :error "unauthorized"
                                              :reason "requires Authorization: CACAO <b64>"} 401)})
      (pc/then
       (-verify-cacao verifier (second m))
       (fn [{:keys [valid? iss error]}]
         (if (and valid? iss)
           {:ok? true :iss iss :response nil}
           {:ok? false :iss nil
            :response (json-response {:ok false :error "unauthorized"
                                      :reason (or error "invalid or expired CACAO")} 401)}))))))

;; ----------------------------- real wiring (:cljs only) -----------------------------

#?(:cljs
   (defrecord LiveCacaoVerifier []
     CacaoVerifier
     (-verify-cacao [_ cacao-b64]
       (.then (cacao/verify cacao-b64)
              (fn [result]
                {:valid? (boolean (aget result "valid"))
                 :iss (aget result "iss")
                 :error (aget result "error")})))))

#?(:cljs
   (defn live-verifier [] (->LiveCacaoVerifier)))
