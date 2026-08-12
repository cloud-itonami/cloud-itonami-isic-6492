(ns credit.kernels.kotoba-oracle
  "Runs the shipped decision core.

  `src/credit/kernels/gate.kotoba` holds the credit governor's rules;
  `src/credit/kernels/gate_kir.cljc` is what was compiled from it and what
  ships. This namespace is the seam, and it is deliberately thin: it
  resolves an artifact, executes an export, and decides nothing.

  ## Why this exists

  `wasm/credit_verdict.kotoba` and `wasm/credit_phase.kotoba` landed with a
  parity test and a host (`credit.kernels.gate-kotoba`) whose own docstring
  said the swap was NOT done. That was the right first step and both are
  still here. But two implementations bound by a test are still two
  implementations, and the measure of the port is not how many host lines
  went away; it is whether the AUTHORITY moved. Until now it had not: the
  `.kotoba` was a checked replica and `credit.kernels.gate` was what ran.
  Now the `.kotoba` is what runs, and the `.cljc` keeps the halves that are
  not decisions -- gathering evidence, naming a code, building an argument.

  ## Why KIR and not the `.wasm` this repo already ships

  Three reasons, in the order they bind:

  1. **The decision path is not JVM.** `credit.governor` and `credit.phase`
     are `.cljc`; the primary gate is ClojureScript
     (`credit.portable-cljs-test-runner`) and the deployed surface is a
     Cloudflare Worker (`shadow-cljs.edn`'s `:edge-api`, reaching the
     governor through `credit.operation`). `credit.kernels.gate-kotoba`
     hosts the `.wasm` through `kototama.tender`/Chicory, which is
     JVM-only with direct Java interop. Delegating to it would move the
     authority onto the one runtime that is NOT production.
  2. **A `.wasm` cannot be asked for a constant.** The legacy emitter
     exports exactly one entry point and reads its inputs out of linear
     memory, so a threshold can only be a literal buried in the body --
     which is the defect ADR-2608120200 recorded here. KIR is a document:
     `confidence-floor-x100` is a named export the host CALLS, and
     `:schemas` carries the record's declared field order.
  3. It is the seam the rest of the fleet already uses -- `cloud-itonami-
     app`, `kotoba-lang/crdt`, `kotoba-lang/com-cloudflare`.

  ## Why the artifact is a `.cljc` and not a resource

  The four existing seams read `resources/**/*.kir.edn` off the classpath.
  That works where the decision runs on the JVM. Here it does not: a
  Cloudflare Worker has no classpath and no filesystem, and neither does
  `cljs.main --target node` for anything outside its compile unit. An EDN
  resource would be readable on exactly the runtime that does not serve
  traffic. So the artifact is `credit.kernels.gate-kir`, a generated
  namespace holding the compiled KIR as one datum -- portable to every
  runtime this actor decides on, and still one artifact, still generated,
  still gated against the source it came from.

  ## No fallback around a missing artifact

  An unknown core, or an artifact that is not a KIR document, throws. It
  does not quietly run something else, because a silent fallback is how a
  decision stops being the one that shipped. Note what this namespace does
  NOT have: a host copy of any rule to fall back TO."
  (:require [credit.kernels.gate-kir :as gate-kir]
            [kotoba.kir :as kir]))

(def shipped
  "Oracle id -> the compiled artifact that ships.

  The `.kotoba` each one came from is named by
  `credit.kernels.kotoba-oracle-gen/cores`, and not here: this namespace is
  loaded by the Worker, the generator is not, and a producer that had to
  require its own output could never write it the first time. The drift
  gate requires both and demands the two id sets match, so a core that is
  declared but not shipped fails rather than disappears."
  {:gate gate-kir/kir})

(def ^:private registered
  "Installed KIR, for the test that has to prove the host reads the shipped
  artifact rather than having quietly kept a copy of the rules."
  (atom {}))

(defn register-kir!
  "Install a KIR for `id`, bypassing the shipped artifact."
  [id k]
  (swap! registered assoc id k)
  k)

(defn deregister-kir!
  "Drop a registration, so `id` runs the shipped artifact again."
  [id]
  (swap! registered dissoc id)
  nil)

(defn- kir-document?
  "Whether `k` is shaped like something `kotoba.kir/execute` can run. Cheap
  on purpose: the point is to refuse an absent or truncated artifact with
  the artifact's name on it, not to re-validate KIR."
  [k]
  (and (map? k) (seq (:functions k))))

(defn kir
  "The KIR for `id`: whatever is registered, else what shipped.

  Throws when neither is a KIR document. That is this seam's one refusal --
  there is nothing else for it to run."
  [id]
  (let [k (or (get @registered id) (get shipped id))]
    (when-not (kir-document? k)
      (throw (ex-info (str "no shipped decision core for " id
                           " -- run `clojure -M:dev:test:gen`")
                      {:oracle id :known (vec (sort (keys shipped)))})))
    k))

(defn signature
  "The shipped declaration of `export`: `:params`, `:param-types`,
  `:result`. This is how a host learns an argument's shape without writing
  it down a second time. Throws if the export is not there, because a host
  asking for a signature is about to build an argument out of it."
  [id export]
  (let [export (symbol (name export))]
    (or (first (filter #(= export (:name %)) (:functions (kir id))))
        (throw (ex-info "shipped core does not declare that export"
                        {:oracle id :export export})))))

(defn param-types
  "Declared parameter types of `export`, in order."
  [id export]
  (:param-types (signature id export)))

(defn schema
  "A `:schemas` entry of the shipped core, resolved. `kotoba.kir/execute`
  wants the record descriptor itself, not the `[:ref …]` a signature names
  it by, so this is the lookup every record argument goes through. Throws
  if the core does not declare it."
  [id schema-name]
  (or (get (:schemas (kir id)) schema-name)
      (throw (ex-info "shipped core does not declare that schema"
                      {:oracle id :schema schema-name}))))

(defn record-fields
  "`[[field type] …]` of a `[:record …]` descriptor, in DECLARED order."
  [record-type]
  (nth record-type 2))

;; ── the guest values that are not plain host values ──────────────────

(defn i64
  "Host integer -> guest `:i64`. A JVM `long` under `:clj`, a `js/BigInt`
  under `:cljs`; neither is what a host flag or currency amount is, and
  keeping the conversion here is what lets a caller not know which runtime
  it is on."
  [n]
  #?(:clj (long n) :cljs (js/BigInt n)))

(defn i64-value
  "Guest `:i64` -> host integer."
  [n]
  #?(:clj n :cljs (js/Number n)))

(defn call
  "Execute an export of the shipped core. Args and result are guest ABI
  values; see `i64` and `record` for the conversions."
  [id export args]
  (kir/execute (kir id) (symbol (name export)) (vec args)))

(defn call-i64
  "Execute an export whose parameters and result are all `:i64`, in host
  integers."
  [id export args]
  (i64-value (call id export (mapv i64 args))))

(defn record
  "Build a record argument for `schema-name` from a host map, in the field
  order the SHIPPED CORE declares. Every field is `:i64`.

  The order is not written down here. A field the core declares and the
  map does not carry is an error, not a zero: the guest would accept the
  positional vector either way, so silence here would be a decision made
  by omission."
  [id schema-name field-values]
  (let [record-type (schema id schema-name)]
    (into [record-type]
          (map (fn [[field _type]]
                 (if-let [entry (find field-values field)]
                   (i64 (val entry))
                   (throw (ex-info "no value for a field the shipped core declares"
                                   {:oracle id :schema schema-name :field field})))))
          (record-fields record-type))))
