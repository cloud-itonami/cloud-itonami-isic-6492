#!/usr/bin/env nbb
;; Resident HTTP host for affordability.wasm -- the cljc/Node.js (no JVM)
;; counterpart to verify_node.cljs, kept running as a `node:http` listener
;; instead of exiting after one call. Reuses the exact same actor-host.js
;; ABI wiring verify_node.cljs already proved works (see that file for the
;; ABI/priority-order rationale); this file only adds the HTTP loop.
;;
;; Run: nbb server.cljs [port]   (default 8479)
;;   GET  /health                -> {"ok":true, ...}
;;   POST /isic-6492/affordability  body {existingDebt,requestedAmount,annualIncome}
;;        -> {"ok":true,"result":0|1,"affordable":bool,"input":{...}}

(ns server
  (:require [clojure.string :as str]
            ["node:fs/promises" :refer [readFile]]
            ["node:http" :as http]
            ["node:process" :refer [argv]]
            ["../../../kotoba-lang/wasm-webcomponent/src/actor-host.js"
             :refer [hostCaps actorHostImports]]))

(def default-port 8479)

(defn- arr [value] (array-seq (or value #js [])))

(defn- script-args []
  (let [items (vec (arr argv))
        idx (.indexOf (clj->js items)
                       (or (some #(when (str/ends-with? (str %) "server.cljs") %) items)
                           (get items 1)))]
    (if (neg? idx) (vec (drop 2 items)) (subvec items (inc idx)))))

(defn- run-affordability [existing-debt requested-amount annual-income wasm-bytes]
  ;; nbb's SCI interop has a runtime bug dispatching chained `.catch` on some
  ;; Promises (repros isolated; unrelated to `try`/`catch` special-form
  ;; usage elsewhere in this file) -- use the two-arg `.then` form
  ;; (onFulfilled/onRejected) throughout this file instead of `.then`+`.catch`
  ;; chains, which sidesteps it.
  (let [memory-box (js-obj)
        import-object #js {:kotoba (actorHostImports #js [] (hostCaps) memory-box)}]
    (.then
     (js/WebAssembly.instantiate wasm-bytes import-object)
     (fn [result]
       (let [instance (.-instance result)
             memory (.. instance -exports -memory)
             view (js/DataView. (.-buffer memory))]
         (set! (.-memory memory-box) memory)
         (.setInt32 view 0 existing-debt true)
         (.setInt32 view 4 requested-amount true)
         (.setInt32 view 8 annual-income true)
         (let [code (js/Number ((.. instance -exports -main)))]
           {:input {:existingDebt existing-debt
                    :requestedAmount requested-amount
                    :annualIncome annual-income}
            :result code
            :affordable (= code 1)}))))))

(defn- read-body [req]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks (atom [])]
       (.on req "data" (fn [chunk] (swap! chunks conj chunk)))
       (.on req "end" (fn [] (resolve (.toString (js/Buffer.concat (clj->js @chunks))))))
       (.on req "error" reject)))))

(defn- send-json [res status body]
  (.writeHead res status #js {"Content-Type" "application/json"})
  (.end res (js/JSON.stringify (clj->js body) nil 2)))

(defn- parse-json [raw]
  (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
       (catch :default _ nil)))

(defn- handle-affordability [req res wasm-bytes]
  (.then
   (read-body req)
   (fn [raw]
     (let [parsed (parse-json raw)
           existing (:existingDebt parsed)
           requested (:requestedAmount parsed)
           income (:annualIncome parsed)]
       (cond
         (nil? parsed)
         (send-json res 400 {:ok false :error "invalid-json"})

         (or (nil? existing) (nil? requested) (nil? income))
         (send-json res 400 {:ok false :error "missing-field"
                              :need ["existingDebt" "requestedAmount" "annualIncome"]})

         :else
         (.then
          (run-affordability existing requested income wasm-bytes)
          (fn [out] (send-json res 200 (assoc out :ok true)))
          (fn [err] (send-json res 500 {:ok false :error (str err)}))))))
   (fn [err] (send-json res 500 {:ok false :error (str err)}))))

(defn- handle-health [res]
  (send-json res 200 {:ok true
                       :service "cloud-itonami-isic-6492-affordability"
                       :runtime "nbb/node (no JVM)"
                       :actor "credit.registry/compute-debt-to-income-ratio"}))

(defn- request-handler [wasm-bytes]
  (fn [req res]
    (let [method (.-method req)
          url (.-url req)]
      (cond
        (and (= method "GET") (= url "/health")) (handle-health res)
        (and (= method "POST") (= url "/isic-6492/affordability")) (handle-affordability req res wasm-bytes)
        :else (send-json res 404 {:ok false :error "not-found"})))))

(defn -main []
  (.then
   (readFile "affordability.wasm")
   (fn [wasm-bytes]
     (let [port (or (some-> (first (script-args)) js/Number) default-port)
           srv (http/createServer (request-handler wasm-bytes))]
       (.listen srv port
                (fn [] (println (str "cloud-itonami-isic-6492 affordability server resident on :" port))))))
   (fn [err] (js/console.error "startup failed:" err) (js/process.exit 1))))

(-main)
