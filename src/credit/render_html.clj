(ns credit.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo had NO demo page and no generator at all. This namespace
  drives the REAL actor stack (`credit.operation` -> `credit.governor`
  -> `credit.store`, through `langgraph.graph/run*`, the same way
  `credit.sim` does) and renders whatever that run actually produced.
  Nothing on the page is hand-typed domain content: every application,
  ratio, violation, disbursement number and ledger fact is read back out
  of the store the run wrote.

  Three DIFFERENT things can stop a commit here, and the page keeps them
  in separate tables because they mean different things and a naive
  `count` over holds would blur them:

    1. HARD governor hold   -- `:t :governor-hold` with a NON-EMPTY
                               `:violations`. The Credit Governor
                               refused. No human can override it.
    2. Rollout-phase hold   -- `:t :governor-hold` with an EMPTY
                               `:violations` and a `:phase-reason`. The
                               governor was CLEAN; `credit.phase`'s
                               rollout gate held the write because the
                               op is not enabled at that phase yet.
    3. Approver rejection   -- `:t :approval-rejected`. The governor
                               escalated, a human looked, and the human
                               said no. Note this fact ALSO carries a
                               non-empty `:violations`
                               (`[{:rule :approver-rejected}]`), so
                               classifying by `:violations` alone would
                               miscount it as a governor refusal --
                               classify on `:t` first.

  `-main` REFUSES to write the file when the run produced no HARD
  governor hold. The point of this console is to show a governor that
  actually refuses; a page rendered from a run where nothing was refused
  would be decoration. That is a build-time invariant, not a convention
  -- see `assert-hard-holds!`.

  Approver attribution is DERIVED at render time (`approver-attribution`)
  by scanning the stored registers for an approver key, rather than
  assuming a behaviour. Measured on this store (2026-08-14): the approver
  survives onto the record for the two effects that persist the
  proposal's payload (`:assessment/set`, `:creditworthiness/set`) and is
  DROPPED by the two path-only effects (`:loan/mark-approved`,
  `:loan/mark-disbursed`, which ignore `:payload` entirely -- see
  `credit.store/commit-record!`), and the append-only ledger records that
  a commit happened but never who approved it. Because the scan is done
  over the live registers, this section self-corrects if the store is
  later changed to retain the approver.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [credit.facts :as facts]
            [credit.governor :as governor]
            [credit.operation :as op]
            [credit.phase :as phase]
            [credit.registry :as registry]
            [credit.store :as store]
            [langgraph.graph :as g])
  (:import [java.util Locale]))

;; ----------------------------- the real run -----------------------------

(def ^:private operator
  "The supervised-auto operator context `credit.sim` uses."
  {:actor-id "op-1" :actor-role :lender :phase 3})

(def ^:private pilot-operator
  "The SAME operator at rollout phase 1 (`assisted-intake`), used to show
  the rollout gate holding a write the governor itself cleared."
  {:actor-id "op-1" :actor-role :lender :phase 1})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- resume! [actor tid status]
  (g/run* actor {:approval {:status status :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through a scenario that reaches every
  disposition this actor can reach, against the ids `credit.store/demo-
  data` actually seeds (`app-1`..`app-4`) -- no invented subjects.

    app-1  full clean lifecycle: intake auto-commits at phase 3 (no
           capital at risk), then jurisdiction assessment,
           creditworthiness screening, loan approval and the loan
           DISBURSEMENT each escalate to a human and are approved.
    app-2  jurisdiction assessment HARD-holds: the applicant's own
           jurisdiction (`ATL`) has no entry in `credit.facts/catalog`,
           so there is no official spec-basis to cite. No test flag is
           used -- the hold comes from the seeded application itself.
    app-3  creditworthiness screening HARD-holds: the application's own
           debt-to-income ratio (0.875, recomputed from its permanent
           `:existing-debt`/`:requested-amount`/`:annual-income`) exceeds
           `credit.registry/affordability-ceiling` (0.43).
    app-4  disbursement HARD-holds twice over: the jurisdiction's
           required evidence was never satisfied AND the application was
           never approved.
    app-1  a SECOND disbursement HARD-holds on double-disbursement.
    app-4  the same creditworthiness screening run at rollout phase 1,
           where the governor is CLEAN but the phase gate holds the
           write (`:phase-reason :phase-disabled`) -- an empty-violation
           hold, deliberately included so the page can show it is NOT a
           governor refusal.
    app-4  and once more at phase 3, where the governor escalates and
           the human operator REJECTS.

  Returns `{:db <store> :runs [{:label .. :thread .. :state ..}]}`. The
  run states are kept because the graph's `:audit` channel is the only
  place an `:approval-granted` fact exists -- the store's ledger never
  sees one (measured; see `approver-attribution`)."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        runs (atom [])
        record! (fn [label thread state] (swap! runs conj {:label label :thread thread :state (:state state)}) state)]
    (record! "app-1 intake (auto-commit)" "app-1-intake"
             (exec! actor "app-1-intake"
                    {:op :application/intake :subject "app-1"
                     :patch {:id "app-1" :applicant "田中 一郎"}} operator))

    (exec! actor "app-1-assess" {:op :jurisdiction/assess :subject "app-1"} operator)
    (record! "app-1 jurisdiction assessment (human-approved)" "app-1-assess"
             (resume! actor "app-1-assess" :approved))

    (exec! actor "app-1-screen" {:op :creditworthiness/screen :subject "app-1"} operator)
    (record! "app-1 creditworthiness screening (human-approved)" "app-1-screen"
             (resume! actor "app-1-screen" :approved))

    (exec! actor "app-1-approve" {:op :loan/approve :subject "app-1"} operator)
    (record! "app-1 loan approval (human-approved)" "app-1-approve"
             (resume! actor "app-1-approve" :approved))

    (exec! actor "app-1-disburse" {:op :loan/disburse :subject "app-1"} operator)
    (record! "app-1 loan disbursement (human-approved actuation)" "app-1-disburse"
             (resume! actor "app-1-disburse" :approved))

    (record! "app-2 jurisdiction assessment (HARD hold)" "app-2-assess"
             (exec! actor "app-2-assess" {:op :jurisdiction/assess :subject "app-2"} operator))

    (record! "app-3 creditworthiness screening (HARD hold)" "app-3-screen"
             (exec! actor "app-3-screen" {:op :creditworthiness/screen :subject "app-3"} operator))

    (record! "app-4 loan disbursement (HARD hold)" "app-4-disburse"
             (exec! actor "app-4-disburse" {:op :loan/disburse :subject "app-4"} operator))

    (record! "app-1 SECOND loan disbursement (HARD hold)" "app-1-disburse-again"
             (exec! actor "app-1-disburse-again" {:op :loan/disburse :subject "app-1"} operator))

    (record! "app-4 creditworthiness screening at phase 1 (rollout gate)" "app-4-screen-phase1"
             (exec! actor "app-4-screen-phase1" {:op :creditworthiness/screen :subject "app-4"} pilot-operator))

    (exec! actor "app-4-screen-rejected" {:op :creditworthiness/screen :subject "app-4"} operator)
    (record! "app-4 creditworthiness screening (human rejected)" "app-4-screen-rejected"
             (resume! actor "app-4-screen-rejected" :rejected))

    {:db db :runs @runs}))

;; ----------------------------- classification -----------------------------

(defn hard-governor-holds
  "Ledger facts where the Credit Governor itself REFUSED: `:t
  :governor-hold` AND a non-empty `:violations`. `:t` is checked first on
  purpose -- an `:approval-rejected` fact also carries a violation."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) ledger))

(defn phase-gate-holds
  "Ledger facts where the governor was CLEAN and the rollout phase gate
  held the write anyway: `:t :governor-hold` with an EMPTY `:violations`
  and a `:phase-reason`."
  [ledger]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:violations %))) ledger))

(defn approver-rejections
  "Ledger facts where a human operator refused an escalated proposal."
  [ledger]
  (filterv #(= :approval-rejected (:t %)) ledger))

(defn assert-hard-holds!
  "Build-time invariant: refuse to render a console from a run in which
  the governor never refused anything. A page showing only green rows
  would not demonstrate a governor at all, and a phase-gate hold (empty
  `:violations`) must not be allowed to satisfy this."
  [ledger]
  (let [hard (hard-governor-holds ledger)]
    (when (empty? hard)
      (throw (ex-info (str "credit.render-html: refusing to write the console -- "
                           "the run produced ZERO HARD governor holds. "
                           "A console that shows no refusal is decoration, not evidence.")
                      {:ledger-facts (count ledger)
                       :hard-governor-holds 0
                       :phase-gate-holds (count (phase-gate-holds ledger))
                       :approver-rejections (count (approver-rejections ledger))})))
    hard))

;; ----------------------------- approver attribution -----------------------------

(def ^:private approver-keys
  "Keys that would carry 'which human approved this'. `:actor` is
  deliberately NOT one of them: it is the actor-id on EVERY fact,
  including auto-commits, so it attributes execution rather than
  approval."
  ["approved-by" "approved_by" "approver" "by"])

(defn- approver-of
  "`[key value]` for the first approver key `m` carries, or nil. Scanned
  rather than assumed, so this reports what the store actually kept."
  [m]
  (when (map? m)
    (first (keep (fn [k]
                   (some (fn [kk] (when-let [v (get m kk)] [kk v]))
                         [(keyword k) k]))
                 approver-keys))))

(def ^:private op->register
  "Which stored register a committed op writes its payload into. Used to
  look up THAT record for the approver scan -- deliberately not a join
  from records back to approvals on `[op subject]`, which is not unique
  and would let a record inherit an unrelated approver."
  {:application/intake      "application"
   :jurisdiction/assess     "assessment"
   :creditworthiness/screen "creditworthiness"
   :loan/approve            "application"
   :loan/disburse           "disbursement"})

(defn- register-record
  "The CURRENT record in `register` for `subject` (the latest committed
  write -- this store keeps one record per subject per register)."
  [db register subject]
  (case register
    "application"      (store/application db subject)
    "assessment"       (store/assessment-of db subject)
    "creditworthiness" (store/creditworthiness-of db subject)
    "disbursement"     (last (filter #(= subject (get % "application_id"))
                                     (store/disbursement-history db)))
    nil))

(defn approvals-granted
  "Every `:approval-granted` fact the graph runs produced, in run order.
  These exist ONLY in the StateGraph's `:audit` channel -- `credit.
  operation`'s `:commit` node writes a `:committed` fact to the store
  ledger and never forwards the approval."
  [runs]
  (vec (for [{:keys [label state]} runs
             f (:audit state)
             :when (= :approval-granted (:t f))]
         (assoc f :label label))))

(defn approver-attribution
  "Derived at render time: for every approval a human actually granted,
  does the record that op wrote still say who approved it? Returns rows
  plus the ledger-wide finding, so the page self-corrects if the store is
  changed to retain the approver."
  [db runs]
  (let [ledger (vec (store/ledger db))
        rows (for [{:keys [op subject by label]} (approvals-granted runs)
                   :let [register (op->register op)
                         record (register-record db register subject)
                         found (approver-of record)]]
               {:label label :op op :subject subject :audit-approver by
                :register register
                :retained? (boolean found)
                :retained-key (some-> found first)
                :retained-value (some-> found second)})]
    {:rows (vec rows)
     :retained (count (filter :retained? rows))
     :total (count rows)
     :ledger-approvers (vec (keep approver-of ledger))
     :ledger-facts (count ledger)}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- fmt3 [x]
  (String/format Locale/ROOT "%.3f" (into-array Object [(double x)])))

(defn- kw-str [k]
  (cond (keyword? k) (subs (str k) 1) (nil? k) "" :else (str k)))

(defn- row [& cells]
  (str "        <tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows-or-empty [rows colspan empty-text]
  (if (seq rows)
    (str/join "\n" rows)
    (str "        <tr><td colspan=\"" colspan "\" class=\"muted\">" empty-text "</td></tr>")))

;; ----------------------------- sections -----------------------------

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">rejected by approver</span>"
      (and (= :governor-hold (:t f)) (seq (:violations f)))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (kw-str (-> f :violations first :rule))) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"warn\">phase hold &middot; " (esc (kw-str (:phase-reason f))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- application-rows [db ledger]
  (for [{:keys [id applicant jurisdiction requested-amount annual-income
                existing-debt credit-score status disbursement-number] :as a}
        (store/all-applications db)
        :let [dti (registry/compute-debt-to-income-ratio a)
              over? (> dti registry/affordability-ceiling)]]
    (row (str "<code>" (esc id) "</code>")
         (esc applicant)
         (esc jurisdiction)
         (str "<span class=\"num\">" (esc requested-amount) "</span>")
         (str "<span class=\"num\">" (esc annual-income) "</span>")
         (str "<span class=\"num\">" (esc existing-debt) "</span>")
         (str "<span class=\"num\">" (esc credit-score) "</span>")
         (str "<span class=\"num " (if over? "critical" "ok") "\">" (fmt3 dti) "</span>")
         (str "<span class=\"" (if (= :disbursed status) "ok" "muted") "\">"
              (esc (kw-str status)) "</span>"
              (when disbursement-number
                (str " <code>" (esc disbursement-number) "</code>")))
         (status-cell ledger id))))

(defn- gate-rows
  "Derived from `credit.phase/phases` and `credit.phase/write-ops` -- the
  actual rollout table, not a description of it. An op that appears in no
  phase's `:auto` set can never auto-commit at any phase; that is a
  structural fact of this actor, read here rather than asserted."
  []
  (let [ordered (sort-by str phase/write-ops)
        phase-nums (sort (keys phase/phases))]
    (for [op ordered
          :let [first-write (first (filter #(contains? (:writes (phase/phases %)) op) phase-nums))
                auto-phases (filter #(contains? (:auto (phase/phases %)) op) phase-nums)]]
      (row (str "<code>" (esc op) "</code>")
           (if first-write
             (str "<span class=\"ok\">phase " first-write " (" (esc (:label (phase/phases first-write))) ")</span>")
             "<span class=\"muted\">never</span>")
           (if (seq auto-phases)
             (str "<span class=\"ok\">phase " (str/join ", " auto-phases) "</span>")
             "<span class=\"warn\">never &mdash; always a human decision</span>")))))

(defn- hard-hold-rows
  "One row per VIOLATION (a single hold can carry several -- app-4's
  disbursement carries two)."
  [holds]
  (for [h holds
        v (:violations h)]
    (row (str "<code>" (esc (:op h)) "</code>")
         (str "<code>" (esc (:subject h)) "</code>")
         (str "<span class=\"critical\">" (esc (kw-str (:rule v))) "</span>")
         (esc (:detail v))
         (str "<span class=\"num\">" (esc (:confidence h)) "</span>"))))

(defn- phase-hold-rows [holds]
  (for [h holds]
    (row (str "<code>" (esc (:op h)) "</code>")
         (str "<code>" (esc (:subject h)) "</code>")
         (str "<span class=\"num\">" (esc (:phase h)) "</span>")
         (str "<span class=\"warn\">" (esc (kw-str (:phase-reason h))) "</span>")
         (str "<span class=\"muted\">" (count (:violations h)) " governor violations &mdash; the governor cleared this</span>"))))

(defn- rejection-rows [rejections]
  (for [r rejections]
    (row (str "<code>" (esc (:op r)) "</code>")
         (str "<code>" (esc (:subject r)) "</code>")
         (esc (:actor r))
         (str "<span class=\"warn\">" (esc (str/join ", " (map kw-str (:basis r)))) "</span>"))))

(defn- approver-rows [{:keys [rows]}]
  (for [{:keys [op subject audit-approver register retained? retained-key retained-value]} rows]
    (row (str "<code>" (esc op) "</code>")
         (str "<code>" (esc subject) "</code>")
         (esc audit-approver)
         (str "<code>" (esc register) "</code>")
         (if retained?
           (str "<span class=\"ok\">" (esc (str retained-key)) " = " (esc retained-value) "</span>")
           "<span class=\"warn\">not retained on record (audit only)</span>"))))

(defn- coverage-rows [db]
  (let [iso3s (sort (distinct (map :jurisdiction (store/all-applications db))))
        {:keys [covered-jurisdictions]} (facts/coverage iso3s)
        covered (set covered-jurisdictions)]
    (for [iso3 iso3s
          :let [sb (facts/spec-basis iso3)]]
      (row (str "<code>" (esc iso3) "</code>")
           (if (covered iso3)
             (str "<span class=\"ok\">" (esc (:owner-authority sb)) "</span>")
             "<span class=\"critical\">no entry in credit.facts/catalog</span>")
           (if sb (esc (:legal-basis sb)) "<span class=\"muted\">&mdash;</span>")
           (if sb
             (str "<span class=\"num\">" (count (:required-evidence sb)) "</span>")
             "<span class=\"num\">0</span>")
           (if sb (str "<code>" (esc (:provenance sb)) "</code>") "<span class=\"muted\">&mdash;</span>")))))

(defn- disbursement-rows [db]
  (for [d (store/disbursement-history db)]
    (row (str "<code>" (esc (get d "record_id")) "</code>")
         (str "<code>" (esc (get d "application_id")) "</code>")
         (str "<span class=\"num\">" (esc (get d "disbursed_amount")) "</span>")
         (esc (get d "jurisdiction"))
         (esc (get d "kind"))
         (if (get d "immutable") "<span class=\"ok\">immutable</span>" "<span class=\"warn\">mutable</span>"))))

(defn- ledger-rows [ledger]
  (for [f ledger]
    (row (esc (kw-str (:t f)))
         (str "<code>" (esc (:op f)) "</code>")
         (str "<code>" (esc (:subject f)) "</code>")
         (esc (kw-str (:disposition f)))
         (esc (str/join ", " (map #(if (keyword? %) (kw-str %) (str %)) (:basis f))))
         (or (some-> (:summary f) esc)
             (some-> (:phase-reason f) kw-str esc)
             "<span class=\"muted\">&mdash;</span>"))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the console from a store `db` and the run states `runs` that
  `run-demo!` produced. Every number here is read back out of the store."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        hard (hard-governor-holds ledger)
        phase-holds (phase-gate-holds ledger)
        rejections (approver-rejections ledger)
        attribution (approver-attribution db runs)
        hard-violations (reduce + (map (comp count :violations) hard))
        hard-kinds (sort (distinct (map (comp kw-str :rule) (mapcat :violations hard))))]
    (str
     "<html><head><meta charset=\"utf-8\">"
     "<title>cloud-itonami-isic-6492 &middot; other credit granting</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Other credit granting (ISIC 6492) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · loan approval and disbursement always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Loan applications</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>credit.store</code> by <code>credit.render-html</code> (<code>clojure -M:dev:render-html</code>). The debt-to-income ratio column is recomputed here by <code>credit.registry/compute-debt-to-income-ratio</code> from each application's own permanent fields — the same function the Credit Governor uses to refuse — against the <code>"
     (fmt3 registry/affordability-ceiling) "</code> affordability ceiling.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Application</th><th>Applicant</th><th>Jurisdiction</th><th>Requested</th><th>Annual income</th><th>Existing debt</th><th>Credit score</th><th>Debt-to-income</th><th>Status</th><th>Last op</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (application-rows db ledger) 10 "no applications") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout gate (credit.phase)</h2>\n"
     "    <p class=\"muted\">Read directly out of <code>credit.phase/phases</code>. An op that appears in no phase's <code>:auto</code> set can never auto-commit at any phase — that is structural, not a milestone still to come. Disbursing real loan funds is additionally held by the governor's <code>:actuation/disburse-loan</code> high-stakes gate, so two independent layers agree.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>First phase that permits the write</th><th>Auto-commit eligible</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (gate-rows) 3 "no write ops") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD governor holds — the Credit Governor refused</h2>\n"
     "    <p class=\"muted\">" (count hard) " hold facts carrying " hard-violations
     " violations across " (count hard-kinds) " rule kinds ("
     (esc (str/join ", " hard-kinds))
     "). These are <strong>not overridable</strong>: they never reach a human at all. The confidence floor for escalation is <code>"
     (esc governor/confidence-floor) "</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Application</th><th>Rule</th><th>Why</th><th>Advisor confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (hard-hold-rows hard) 5 "no HARD holds") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout-phase holds — the governor was clean</h2>\n"
     "    <p class=\"muted\">A DIFFERENT thing from the table above. These facts share the <code>:governor-hold</code> type but carry an <strong>empty</strong> <code>:violations</code> and a <code>:phase-reason</code>: the Credit Governor cleared the proposal and <code>credit.phase</code>'s rollout gate held the write because the op is not enabled at that phase. Counting holds without separating these would report a rollout pause as a compliance refusal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Application</th><th>Phase</th><th>Phase reason</th><th>Governor verdict</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (phase-hold-rows phase-holds) 5 "no rollout-phase holds in this run") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Approver rejections — a human said no</h2>\n"
     "    <p class=\"muted\">The governor escalated, a human operator looked, and refused. These carry <code>:t :approval-rejected</code> and a <code>:rule :approver-rejected</code> violation — so a hold count keyed on <code>:violations</code> alone would misfile them as governor refusals.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Application</th><th>Operator</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (rejection-rows rejections) 4 "no rejections in this run") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Approver attribution</h2>\n"
     "    <p class=\"muted\">Derived at render time by scanning each written register for an approver key ("
     (esc (str/join ", " approver-keys))
     ") — not asserted, so this table self-corrects if the store changes. "
     (:retained attribution) " of " (:total attribution)
     " human approvals in this run are still attributable on the committed record. "
     (if (seq (:ledger-approvers attribution))
       (str "The append-only ledger carries an approver on "
            (count (:ledger-approvers attribution)) " of its " (:ledger-facts attribution) " facts.")
       (str "The append-only ledger (" (:ledger-facts attribution)
            " facts) records that a commit happened but <strong>never who approved it</strong>: <code>credit.operation</code>'s <code>:commit</code> node writes a <code>:committed</code> fact and does not forward the <code>:approval-granted</code> audit fact, which exists only in the StateGraph's <code>:audit</code> channel."))
     " Rows marked <em>audit only</em> are approvals whose target effect (<code>:loan/mark-approved</code>, <code>:loan/mark-disbursed</code>) writes by path and ignores <code>:payload</code>, so the approver never reaches the record.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Application</th><th>Approved by (run audit)</th><th>Register written</th><th>Retained on record?</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (approver-rows attribution) 5 "no human approvals in this run") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Jurisdiction spec-basis coverage</h2>\n"
     "    <p class=\"muted\">Every jurisdiction present in the seeded applications, checked against <code>credit.facts/catalog</code>. A jurisdiction with no entry has <strong>no</strong> spec-basis — the advisor must not invent one, and the governor holds if it tries. Coverage is reported honestly, never padded.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ISO3</th><th>Owner authority</th><th>Legal basis</th><th>Required evidence</th><th>Provenance</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (coverage-rows db) 5 "no jurisdictions") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Loan-disbursement records</h2>\n"
     "    <p class=\"muted\">Append-only drafts built by <code>credit.registry/register-loan-disbursement</code>. This actor builds the record a lender keeps; it does not touch any real loan-servicing or banking system, and every certificate it produces is unsigned — signature is the licensed lender's act.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Disbursement no.</th><th>Application</th><th>Disbursed amount</th><th>Jurisdiction</th><th>Kind</th><th>History</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (disbursement-rows db) 6 "no disbursements") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">The append-only decision-fact log the store actually holds after this scenario — " (count ledger) " facts.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Application</th><th>Disposition</th><th>Basis</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n"
     (rows-or-empty (ledger-rows ledger) 6 "empty ledger") "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>credit.render-html</code> from a real <code>credit.operation</code> StateGraph run against <code>credit.store/seed-db</code>. No hand-written rows, no timestamps, byte-identical across reruns on the same seed. The renderer refuses to write this file if the run produces no HARD governor hold.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        hard (assert-hard-holds! ledger)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count hard) " HARD governor holds / "
                  (reduce + (map (comp count :violations) hard)) " violations, "
                  (count (phase-gate-holds ledger)) " rollout-phase holds, "
                  (count (approver-rejections ledger)) " approver rejections, "
                  (count (store/disbursement-history db)) " disbursements, "
                  (count (approvals-granted runs)) " human approvals granted)"))))
