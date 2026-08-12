(ns credit.kernels.gate-kotoba
  "A SECOND execution path for `credit.kernels.gate`'s `verdict-code`/
  `phase-disposition`/`phase-reason` -- same signatures, decided by the
  compiled `.kotoba`/WASM twins (`wasm/credit_verdict.kotoba`/
  `wasm/credit_phase.kotoba`, see `wasm/README.md`) via `kototama.tender`
  rather than in-process Clojure.

  ## What this namespace is now, and what it was

  Until 2026-08-12 this docstring said the swap to a `.kotoba`-decided
  gate was NOT done, and it was right: `credit.kernels.gate` held the
  rules and this namespace was an unused drop-in. ADR-2608120200 recorded
  that as one of five dangerous shapes in the fleet.

  The swap is now done -- but NOT through here. `credit.kernels.gate`
  delegates to `src/credit/kernels/gate.kotoba`, compiled to KIR, shipped
  as `credit.kernels.gate-kir`, executed through
  `credit.kernels.kotoba-oracle`. The reasons are in that namespace's
  docstring and they are about WHERE this actor decides: the governor is
  reached from a Cloudflare Worker and from `cljs.main`, and
  `kototama.tender` is JVM-only (Chicory, direct Java interop). Routing
  the live decision through here would have moved the authority onto the
  one runtime that does not serve traffic. A `.wasm` also cannot be asked
  for a threshold -- one entry point, inputs in linear memory -- which is
  the specific defect that had to be closed.

  So this stays as what it honestly is: an independent implementation of
  the same rules on a different execution path, reached only from tests.
  `credit.kernels.gate-kotoba-test` runs it against
  `credit.kernels.gate` -- i.e. against the shipped core -- over all 52
  battery cases, which is the check worth having from a second
  implementation. It decides nothing in production.

  Requires `kototama.tender` (and therefore Chicory, transitively) on
  the classpath -- kept out of this repo's main `:deps` (only in the
  `:test` alias, see `deps.edn`) so requiring `credit.governor`/
  `credit.phase` never forces it on a consumer."
  (:require [clojure.java.io :as io]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes [filename]
  (.readAllBytes (io/input-stream (io/file (str "wasm/" filename)))))

(def ^:private verdict-bytes (delay (wasm-bytes "credit_verdict.wasm")))
(def ^:private phase-bytes (delay (wasm-bytes "credit_phase.wasm")))

(defn- run-main [wasm-bytes writes]
  (let [instance (tender/instantiate wasm-bytes [] (contract/host-caps {}))
        memory (.memory instance)]
    (doseq [[offset value] writes] (.writeI32 memory offset value))
    (tender/call-main instance)))

(defn verdict-code
  "Same contract as `credit.kernels.gate/verdict-code`: 0 ok/commit-
  eligible, 1 escalate, 2 hard-hold. See `wasm/credit_verdict.kotoba`'s
  ns docstring for the memory-offset ABI this wraps."
  [spec-missing evidence-incomplete not-approved double-disb
   afford-applicable afford-total afford-income confidence-x100 actuation]
  (run-main @verdict-bytes
            [[0 spec-missing] [4 evidence-incomplete] [8 not-approved] [12 double-disb]
             [16 afford-applicable] [20 afford-total] [24 afford-income]
             [28 confidence-x100] [32 actuation]]))

(defn- run-phase [phase op governor-disposition]
  (run-main @phase-bytes [[0 phase] [4 op] [8 governor-disposition]]))

(defn phase-disposition
  "Same contract as `credit.kernels.gate/phase-disposition`: 0 commit,
  1 escalate, 2 hold. `wasm/credit_phase.kotoba`'s `main` packs both
  disposition and reason into one return value (`10*disposition +
  reason`, see its own ns docstring) -- unpacked here via `quot`."
  [phase op governor-disposition]
  (quot (run-phase phase op governor-disposition) 10))

(defn phase-reason
  "Same contract as `credit.kernels.gate/phase-reason`: 0 none,
  1 phase-disabled, 2 phase-approval. Unpacked from the same packed
  return value `phase-disposition` reads, via `rem` -- see that fn's
  docstring."
  [phase op governor-disposition]
  (rem (run-phase phase op governor-disposition) 10))
