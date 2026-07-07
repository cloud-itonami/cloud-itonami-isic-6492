#!/usr/bin/env nbb
;; Node-hosted (no JVM) execution of affordability.wasm through
;; kotoba-lang/wasm-webcomponent's actor-host.js port of the actor:host ABI.
;;
;; ClojureScript-on-Node via nbb instead of plain JS/.mjs, per this
;; monorepo's kotoba-wasm > clojurewasm > cljs > nbb > jvm runtime priority
;; (root CLAUDE.md) -- ported from the original wasm/verify_node.mjs.
;;
;; Why this exists: kototama.tender (JVM/Chicory, see
;; test/wasm/affordability_test.clj) is the more mature host, but the
;; murakumo fleet's Mac-mini nodes have no JVM installed (only the pinned
;; Rust kotoba/kotoba-server binaries + a native XMRig miner) -- confirmed
;; directly, 2026-07-07: `java -version` on asher/naphtali/judah/zebulun/
;; issachar all report "Unable to locate a Java Runtime" (ADR-2607072530).
;; Node.js has no such gap, and kotoba-lang/wasm-webcomponent already ships
;; a real, tested, dependency-free actor:host port that runs under plain
;; `node` (or, here, `nbb`).
;;
;; affordability.wasm needs zero host imports (pure arithmetic, no
;; capabilities) -- simpler than the isic-6511 precedent this pattern is
;; copied from (underwriting_decision.wasm needs log-write + llm-infer).
;;
;; ABI: main is 0-arity; the three real i32 inputs (existing-debt,
;; requested-amount, annual-income -- all cents) are written into the
;; guest's exported linear memory at offsets 0/4/8 before calling main().
;; WASM linear memory is little-endian.
;;
;; Assumes the fixed sibling-checkout layout this monorepo's west manifest
;; always uses (orgs/<org>/<repo>) -- not a portable npm dependency.
;;
;; Run: nbb verify_node.cljs <scenario>
;;   scenarios: approve | reject | zero-income
;;   or: nbb verify_node.cljs <existing-debt> <requested-amount> <annual-income>

(ns verify-node
  (:require [clojure.string :as str]
            ["node:fs/promises" :refer [readFile]]
            ["node:process" :refer [argv]]
            ["../../../kotoba-lang/wasm-webcomponent/src/actor-host.js"
             :refer [hostCaps actorHostImports]]))

(def scenarios
  {"approve"     [500000 2000000 6000000]
   "reject"      [3000000 3000000 6000000]
   "zero-income" [0 0 0]})

(defn- arr [value] (array-seq (or value #js [])))

(defn- script-path []
  (or (some #(when (str/ends-with? (str %) "verify_node.cljs") %) (arr argv))
      (aget argv 1)
      "verify_node.cljs"))

(defn- cli-args []
  (let [items (vec (arr argv))
        script (script-path)
        index (.indexOf (clj->js items) script)]
    (if (neg? index)
      (vec (drop 2 items))
      (subvec items (inc index)))))

(defn- resolve-inputs [args]
  (cond
    (= 3 (count args))
    (mapv js/Number args)

    (contains? scenarios (first args))
    (get scenarios (first args))

    :else
    (do
      (js/console.error
       (str "usage: nbb verify_node.cljs <scenario|existing-debt requested-amount annual-income>\n"
            "scenarios: " (str/join ", " (keys scenarios))))
      (js/process.exit 64))))

(defn- run [[existing-debt requested-amount annual-income :as inputs]]
  (-> (readFile "affordability.wasm")
      (.then
       (fn [wasm-bytes]
         (let [memory-box (js-obj)
               import-object #js {:kotoba (actorHostImports #js [] (hostCaps) memory-box)}]
           (-> (js/WebAssembly.instantiate wasm-bytes import-object)
               (.then
                (fn [result]
                  (let [instance (.-instance result)
                        memory (.. instance -exports -memory)
                        view (js/DataView. (.-buffer memory))]
                    (set! (.-memory memory-box) memory)
                    (.setInt32 view 0 existing-debt true)
                    (.setInt32 view 4 requested-amount true)
                    (.setInt32 view 8 annual-income true)
                    ;; main's result-type is :i32 (never :i64/bigint), so no
                    ;; bigint->Number coercion is needed here.
                    (let [code (js/Number ((.. instance -exports -main)))]
                      (println
                       (str (js/JSON.stringify
                             (clj->js {:input {:existingDebt existing-debt
                                                :requestedAmount requested-amount
                                                :annualIncome annual-income}
                                       :result code
                                       :affordable (= code 1)})
                             nil 2)))))))))))))

(run (resolve-inputs (cli-args)))
