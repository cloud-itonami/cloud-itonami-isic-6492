(ns credit.facts
  "Per-jurisdiction consumer-credit/truth-in-lending regulatory catalog
  -- the G2-style spec-basis table the Credit Governor checks every
  jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's lender-registration/
  truth-in-lending disclosure requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official consumer-
  credit regulator (see `:provenance`); they are a STARTING catalog,
  not a from-scratch survey of all ~194 jurisdictions. Extending
  coverage is additive: add one map to `catalog`, cite a real source,
  done -- never invent a jurisdiction's requirements to make coverage
  look bigger.

  MORTGAGES: this is a CONSUMER-CREDIT table. For credit secured on
  real property it can be the WRONG regime rather than an incomplete
  one (JPN's 総量規制 does not reach a bank 住宅ローン; GBR mortgages
  are MCOB, not CONC). See `real-property-secured-delta` and
  `generic-entry-misleading-for-mortgage?` below, and the sibling
  repository `cloud-itonami/mortgage-registry` for the mortgage
  procedure/support/organization planes themselves.

  Like `brokerage.facts`'s/`pension.facts`'s `USA` (not `USA-NY`),
  consumer-credit truth-in-lending disclosure in the US IS federally
  regulated (Truth in Lending Act / Regulation Z, enforced by the
  CFPB) -- unlike insurance/real-estate licensing, which is per-state
  -- so this catalog's US entry is `USA`, a genuine national
  authority, not a state exemplar.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  loan-application/income-verification/disclosure evidence set
  submitted in some form; `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "貸金業法 (Money Lending Business Act)"
          :national-spec "貸金業法 総量規制・説明義務に関する内閣府令"
          :provenance "https://www.fsa.go.jp/"
          :required-evidence ["借入申込書 (loan application form)"
                              "収入証明書 (income/creditworthiness verification)"
                              "契約締結前交付書面 (truth-in-lending disclosure statement)"
                              "貸金業登録票 (lender registration/license certificate)"]}
   "USA" {:name "United States"
          :owner-authority "Consumer Financial Protection Bureau (CFPB)"
          :legal-basis "Truth in Lending Act (TILA) / Regulation Z (12 CFR Part 1026)"
          :national-spec "Regulation Z §1026.18 (disclosure requirements) + Ability-to-Repay/Qualified Mortgage rule"
          :provenance "https://www.consumerfinance.gov/"
          :required-evidence ["Loan application form"
                              "Income / creditworthiness verification"
                              "Truth-in-lending disclosure statement"
                              "Lender registration / license certificate"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Consumer Credit Act 1974 (as amended) / FCA CONC Sourcebook"
          :national-spec "FCA Handbook CONC 4-6 (disclosure and creditworthiness assessment)"
          :provenance "https://www.fca.org.uk/"
          :required-evidence ["Loan application form"
                              "Income / creditworthiness verification"
                              "Pre-contract credit information (SECCI)"
                              "Lender registration / consumer-credit permission"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Bürgerliches Gesetzbuch (BGB) §491 ff. (Verbraucherdarlehensvertrag)"
          :national-spec "BaFin-Rundschreiben zur Kreditwürdigkeitsprüfung (Verbraucherkreditrichtlinie-Umsetzung)"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Darlehensantrag (loan application form)"
                              "Einkommensnachweis (income/creditworthiness verification)"
                              "Vorvertragliche Informationen (truth-in-lending disclosure statement)"
                              "Erlaubnis nach § 34c GewO (lender registration/license certificate)"]}
   "IDN" {:name "Indonesia"
          :owner-authority "Otoritas Jasa Keuangan (OJK / Financial Services Authority)"
          :legal-basis "Undang-Undang Nomor 4 Tahun 2023 tentang Pengembangan dan Penguatan Sektor Keuangan (P2SK Law), amending Undang-Undang Nomor 21 Tahun 2011 tentang Otoritas Jasa Keuangan"
          :national-spec "Peraturan OJK (POJK) Nomor 40 Tahun 2024 tentang Layanan Pendanaan Bersama Berbasis Teknologi Informasi (LPBBTI) -- Pasal 10 ayat (1) (izin usaha wajib), Pasal 148 ayat (1) (mitigasi risiko/verifikasi identitas), Pasal 145 ayat (2) huruf f (pengungkapan manfaat ekonomi Pendanaan)"
          :provenance "https://www.ojk.go.id/id/regulasi/Pages/POJK-40-Tahun-2024-Layanan-Pendanaan-Bersama-Berbasis-Teknologi-Informasi.aspx"
          :required-evidence ["Perjanjian Pendanaan / permohonan Pendanaan (funding application document, POJK 40/2024 Pasal 144-145)"
                              "Analisis risiko Pendanaan dan verifikasi identitas Pengguna (creditworthiness/identity verification, Pasal 148 ayat (1) huruf a-b)"
                              "Pengungkapan manfaat ekonomi Pendanaan dalam Perjanjian Pendanaan (truth-in-lending/cost-of-funding disclosure, Pasal 145 ayat (2) huruf f)"
                              "Izin usaha Penyelenggara dari Otoritas Jasa Keuangan (lender/operator business license certificate, Pasal 10 ayat (1))"]}})

;; --- Real-property-secured (mortgage) credit: where `catalog` above does NOT
;; --- apply, or does not apply alone ---------------------------------------
;;
;; This actor's scope is unsecured/generic consumer credit granting. When the
;; credit is secured on real property, `catalog` above can be the WRONG regime
;; -- not merely an incomplete one -- and an advisor that cites it for a
;; mortgage would be citing a real source for the wrong thing. That failure
;; mode is exactly what the Credit Governor exists to catch, so the exceptions
;; are recorded here as data rather than left to the advisor to know.
;;
;; The authority for the mortgage side (procedure, security instrument, public
;; support programmes, organizations) is the sibling repository
;; `cloud-itonami/mortgage-registry` (`mortgage.facts/catalog`). This table
;; holds only the DELTA against `catalog` above, so the two never restate each
;; other and cannot drift apart in silence.

(def real-property-secured-delta
  "iso3 -> how a loan secured on real property differs from the generic
  consumer-credit entry in `catalog`. nil for a jurisdiction means only that
  no delta has been researched -- NOT that `catalog` applies unchanged."
  {"JPN"
   {:generic-entry-applies? false
    :why "`catalog` \"JPN\" cites 貸金業法 (Money Lending Business Act) and its 総量規制. Neither is the operative regime for a typical Japanese housing loan: 金融庁 states 「総量規制が適用されるのは、貸金業者から個人が借入れを行う場合です。銀行からの借入れや法人名義での借入れは対象外です」, and most 住宅ローン are bank lending under 銀行法, outside 貸金業法 entirely. 金融庁 separately states 「住宅ローンなど、一般に低金利で返済期間が長く、定型的である一部の貸付けについては、総量規制は適用されません」 -- so even where a 貸金業者 does lend, the income-multiple ceiling in `catalog` does not bind."
    :operative-instead "銀行法 (bank lending) + 不動産登記法 for 抵当権設定登記; see mortgage-registry mortgage.facts/catalog \"JPN\""
    :provenance ["https://www.fsa.go.jp/policy/kashikin/qa.html"
                 "https://www.fsa.go.jp/policy/kashikin/kihon.html"]
    :retrieved-at "2026-08-01"}

   "GBR"
   {:generic-entry-applies? false
    :why "`catalog` \"GBR\" cites the Consumer Credit Act 1974 and the FCA CONC sourcebook. Since 21 March 2016 those are NOT the mortgage regime: FCA MCOB 1.6 states that \"Virtually all agreements secured on land will now be subject to MCOB\", and that a credit agreement secured on land which is not a regulated mortgage contract (for example because the borrower is not an individual or a trustee) \"may be a regulated credit agreement to which the CCA and CONC apply\" -- i.e. CONC is the residual case, not the default."
    :operative-instead "FCA Handbook MCOB (Mortgages and Home Finance: Conduct of Business), incl. MCOB 11.6 responsible lending; see mortgage-registry mortgage.facts/catalog \"GBR\""
    :provenance ["https://www.handbook.fca.org.uk/handbook/MCOB/1/6.html"
                 "https://handbook.fca.org.uk/handbook/perg4/perg4s4"]
    :retrieved-at "2026-08-01"
    :verification-note "MCOB rule text is rendered client-side and could not be fetched directly; the quoted MCOB 1.6 wording came from the search index of that official Handbook page on 2026-08-01, not from a direct read."}

   "USA"
   {:generic-entry-applies? true
    :additional-requirements true
    :why "`catalog` \"USA\" (TILA / Regulation Z, CFPB) does apply, and already names the Ability-to-Repay/Qualified Mortgage rule. Real-property security adds the TILA-RESPA Integrated Disclosure timing at 12 CFR 1026.19(e) and (f), which covers a \"closed-end consumer credit transaction secured by real property or a cooperative unit, other than a reverse mortgage\": the Loan Estimate no later than three business days after the creditor receives the application, and the Closing Disclosure received not later than three business days before consummation."
    :operative-additionally "12 CFR 1026.19(e), (f); see mortgage-registry mortgage.facts/catalog \"USA\""
    :provenance ["https://www.consumerfinance.gov/rules-policy/regulations/1026/19/"]
    :retrieved-at "2026-08-01"}

   "DEU"
   {:generic-entry-applies? :partly
    :why "`catalog` \"DEU\" cites BGB §491 ff. (Verbraucherdarlehensvertrag), which is the right statute family, but BGB §491 distinguishes the Allgemein-Verbraucherdarlehensvertrag from the Immobiliar-Verbraucherdarlehensvertrag; a property-secured loan is the latter and additionally requires notarisation of the purchase contract under BGB §311b and entry of the Grundschuld in the Grundbuch."
    :operative-additionally "BGB §311b + Grundbuchordnung; see mortgage-registry mortgage.facts/catalog \"DEU\""
    :provenance ["https://www.gesetze-im-internet.de/bgb/__311b.html"]
    :retrieved-at "2026-08-01"
    :verification-note "The §491 Allgemein/Immobiliar split was NOT re-fetched from gesetze-im-internet in this session; only the §311b citation carries a fetched provenance. Read §491 before relying on the distinction as worded here."}})

(defn mortgage-delta
  "How real-property security changes (or replaces) `catalog`'s entry for
  `iso3`, or nil when no delta has been researched. nil is NOT a statement
  that the generic entry applies unchanged."
  [iso3]
  (get real-property-secured-delta iso3))

(defn generic-entry-misleading-for-mortgage?
  "True when citing `catalog`'s entry for a real-property-secured loan would
  cite the WRONG regime (not merely an incomplete one). The Credit Governor
  should treat a mortgage proposal citing such an entry as unsupported."
  [iso3]
  (false? (:generic-entry-applies? (mortgage-delta iso3))))

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to disburse a
  loan on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6492 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `credit.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
