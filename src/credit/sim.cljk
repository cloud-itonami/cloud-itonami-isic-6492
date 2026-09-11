(ns credit.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean application
  through intake -> jurisdiction truth-in-lending assessment ->
  creditworthiness screening -> loan approval -> loan-disbursement
  proposal (always escalates) -> human approval -> commit, then shows
  four HARD holds (a jurisdiction with no spec-basis, an application
  whose own debt-to-income ratio exceeds the affordability ceiling, a
  disbursement attempt against an application that was never approved,
  and a double-disbursement of an already-disbursed application) that
  never reach a human at all, and prints the audit ledger + the draft
  loan-disbursement records."
  (:require [langgraph.graph :as g]
            [credit.store :as store]
            [credit.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :lender :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== application/intake app-1 (JPN, clean; debt-to-income ratio 0.25) ==")
    (println (exec! actor "t1" {:op :application/intake :subject "app-1"
                                :patch {:id "app-1" :applicant "田中 一郎"}} operator))

    (println "== jurisdiction/assess app-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator))
    (println (approve! actor "t2"))

    (println "== creditworthiness/screen app-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :creditworthiness/screen :subject "app-1"} operator))
    (println (approve! actor "t3"))

    (println "== loan/approve app-1 (escalates -- human approves) ==")
    (println (exec! actor "t4" {:op :loan/approve :subject "app-1"} operator))
    (println (approve! actor "t4"))

    (println "== loan/disburse app-1 (always escalates -- actuation/disburse-loan) ==")
    (let [r (exec! actor "t5" {:op :loan/disburse :subject "app-1"} operator)]
      (println r)
      (println "-- human lender approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess app-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "app-2" :no-spec? true} operator))

    (println "== creditworthiness/screen app-3 (debt-to-income ratio 0.875 exceeds the 0.43 ceiling -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t7" {:op :creditworthiness/screen :subject "app-3"} operator))

    (println "== loan/disburse app-4 (never approved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :loan/disburse :subject "app-4"} operator))

    (println "== loan/disburse app-1 AGAIN (double-disbursement of an already-disbursed application -> HARD hold) ==")
    (println (exec! actor "t9" {:op :loan/disburse :subject "app-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft loan-disbursement records ==")
    (doseq [r (store/disbursement-history db)] (println r))))
