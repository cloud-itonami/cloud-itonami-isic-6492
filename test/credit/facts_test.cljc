(ns credit.facts-test
  (:require [clojure.test :refer [deftest is]]
            [credit.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

;; --- real-property-secured (mortgage) delta -------------------------------

(deftest mortgage-delta-flags-the-wrong-regime-not-just-a-gap
  (is (facts/generic-entry-misleading-for-mortgage? "JPN")
      "JPN catalog cites 貸金業法/総量規制, which is not the housing-loan regime")
  (is (facts/generic-entry-misleading-for-mortgage? "GBR")
      "GBR catalog cites CCA1974/CONC; since 2016-03-21 mortgages are MCOB")
  (is (not (facts/generic-entry-misleading-for-mortgage? "USA"))
      "USA catalog (TILA/Reg Z) does apply; TRID timing is additive"))

(deftest mortgage-delta-absence-is-not-a-clearance
  (is (nil? (facts/mortgage-delta "IDN"))
      "no delta researched for IDN")
  (is (not (facts/generic-entry-misleading-for-mortgage? "IDN"))
      "and an unresearched jurisdiction must NOT be reported as misleading either -- callers read `mortgage-delta` for nil, they do not infer clearance from this predicate")
  (is (nil? (facts/mortgage-delta "ATL"))))

(deftest every-mortgage-delta-cites-a-source
  (doseq [[iso3 delta] facts/real-property-secured-delta]
    (is (seq (:provenance delta)) (str iso3 " delta must cite a source"))
    (is (every? #(clojure.string/starts-with? % "https://") (:provenance delta))
        (str iso3 " delta provenance must be https URLs"))
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (str (:retrieved-at delta)))
        (str iso3 " delta must record when it was read"))
    (is (seq (:why delta)) (str iso3 " delta must say WHY it differs"))))

(deftest mortgage-delta-defers-to-the-registry-not-restating-it
  (doseq [[_ delta] facts/real-property-secured-delta]
    (is (clojure.string/includes?
         (str (:operative-instead delta) (:operative-additionally delta))
         "mortgage-registry")
        "each delta must point at the authority instead of restating it")))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
