(ns credit.kernels.gate-kotoba
  "A verified, drop-in-compatible alternative to
  `credit.kernels.gate`'s `verdict-code`/`phase-disposition`/
  `phase-reason` -- same signatures, decided by the compiled `.kotoba`/
  WASM twins (`wasm/credit_verdict.kotoba`/`wasm/credit_phase.kotoba`,
  see `wasm/README.md`) via `kototama.tender` instead of in-process
  Clojure. ADR-2607151500 addendum 4: the same \"wire a verified drop-in
  function without flipping the live call site\" pattern already applied
  to `kototama.unspsc.prior-shortcut-kotoba` for the kototama-internal
  narrow-slice port, applied here to the ORIGINAL cloud-itonami governor
  port.

  `credit.governor/check` (line ~227) calls `gate/verdict-code` directly
  with 9 positional args; `credit.phase/gate` (lines ~103-104) calls
  `kernel/phase-disposition` and `kernel/phase-reason` with 3 positional
  args each -- both call sites could be pointed at this namespace's
  functions instead with NO OTHER CHANGE (same arg order, same wire
  codes), but that swap is NOT done here. Flipping either call site
  means every proposal `credit.governor/check` evaluates crosses a real
  WASM instantiation boundary in production -- a real production-
  behavior and performance-profile change to a live decision gate, left
  for an explicit owner decision, not an autonomous substitution (same
  reasoning `kototama.unspsc.prior-shortcut-kotoba`'s own docstring
  gives for `kototama.unspsc.organism`).

  Requires `kototama.tender` (and therefore Chicory, transitively) on
  the classpath -- kept out of this repo's main `:deps` (only in the
  `:test` alias today, see `deps.edn`'s own comment) so requiring
  `credit.governor`/`credit.phase` never forces it on a consumer who
  doesn't want the WASM-backed variant. A caller who DOES want this
  namespace adds `io.github.kotoba-lang/kototama` to their own project
  (or activates this repo's `:test` alias)."
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
