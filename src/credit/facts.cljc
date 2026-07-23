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
