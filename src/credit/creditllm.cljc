(ns credit.creditllm
  "Credit-LLM client -- the *contained intelligence node* for the
  credit-granting actor.

  It normalizes application intake, drafts a per-jurisdiction truth-
  in-lending disclosure checklist, screens applications for a
  creditworthiness/affordability signal, drafts the loan-approval
  decision, and drafts the loan-disbursement action. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  loan disbursement. Every output is censored downstream by `credit.
  governor` before anything touches the SSoT, and `:loan/disburse`
  proposals NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/disburse-loan | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [credit.facts :as facts]
            [credit.registry :as registry]
            [credit.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the applicant, requested amount, income/debt or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "融資申込レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :application/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction truth-in-lending disclosure checklist draft. `:no-
  spec?` injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in `credit.
  facts` -- the Credit Governor must reject this (never invent a
  jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/application db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "credit.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-creditworthiness
  "Creditworthiness/affordability screening draft -- computes the
  application's own debt-to-income ratio via `registry/compute-debt-
  to-income-ratio`. Injects the failure mode: the Credit Governor must
  HOLD, un-overridably, on any application whose debt-to-income ratio
  exceeds `registry/affordability-ceiling`."
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        dti (registry/compute-debt-to-income-ratio a)
        affordable? (<= dti registry/affordability-ceiling)]
    {:summary    (str subject ": 負債収入比率=" dti (if affordable? " (許容範囲内)" " (許容上限超過)"))
     :rationale  (str "existing-debt=" (:existing-debt a) " requested-amount=" (:requested-amount a)
                      " annual-income=" (:annual-income a) " ceiling=" registry/affordability-ceiling)
     :cites      [:debt-to-income-ratio]
     :effect     :creditworthiness/set
     :value      {:application-id subject :debt-to-income-ratio dti
                 :verdict (if affordable? :affordable :unaffordable)}
     :stake      nil
     :confidence (if affordable? 0.9 0.3)}))

(defn- propose-approval
  "Draft the loan-APPROVAL decision -- a genuine underwriting judgment,
  not just data normalization. Never a draft the actor may auto-run
  (see README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set, `credit.phase`)."
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        cw (store/creditworthiness-of db subject)
        clean? (= :affordable (:verdict cw))]
    {:summary    (str subject " (" (:applicant a) ") の融資承認提案")
     :rationale  (if cw
                   (str "creditworthiness verdict: " (:verdict cw))
                   "creditworthiness未実施")
     :cites      (if cw [subject] [])
     :effect     :loan/mark-approved
     :value      {:application-id subject}
     :stake      nil
     :confidence (if clean? 0.9 0.3)}))

(defn- propose-disbursement
  "Draft the actual loan-DISBURSEMENT action -- disbursing real loan
  funds to an applicant. ALWAYS `:stake :actuation/disburse-loan` --
  this is a REAL-WORLD act (real money leaves the lender), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`credit.phase`); the governor
  also always escalates on `:actuation/disburse-loan`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        dti (registry/compute-debt-to-income-ratio a)
        affordable? (<= dti registry/affordability-ceiling)]
    {:summary    (str subject " 向け融資実行提案 (requested=" (:requested-amount a) ")")
     :rationale  (str "status=" (:status a) " debt-to-income-ratio=" dti)
     :cites      [subject]
     :effect     :loan/mark-disbursed
     :value      {:application-id subject}
     :stake      :actuation/disburse-loan
     :confidence (if (and affordable? (= :approved (:status a))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :application/intake       (normalize-intake db request)
    :jurisdiction/assess        (assess-jurisdiction db request)
    :creditworthiness/screen    (screen-creditworthiness db request)
    :loan/approve                 (propose-approval db request)
    :loan/disburse                 (propose-disbursement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは消費者金融の融資審査・実行エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:application/upsert|:assessment/set|:creditworthiness/set|"
       ":loan/mark-approved|:loan/mark-disbursed) "
       ":stake(:actuation/disburse-loan か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess     {:application (store/application st subject)}
    :creditworthiness/screen {:application (store/application st subject)}
    :loan/approve            {:application (store/application st subject)
                              :creditworthiness (store/creditworthiness-of st subject)}
    :loan/disburse           {:application (store/application st subject)}
    {:application (store/application st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Credit Governor escalates/
  holds -- an LLM hiccup can never auto-disburse a loan."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :creditllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
