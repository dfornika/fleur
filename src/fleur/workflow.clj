(ns fleur.workflow
  "Execution of CWL `Workflow` processes (linear / static-DAG; no scatter or
   conditional `when` yet).

   A Workflow wires `steps` together: each step's `in` entries pull from a
   `source` (a workflow input id, or a `stepid/outputid` from an upstream step),
   and the workflow's `outputs` pull from step outputs via `outputSource`. We
   model the step dependencies as an ubergraph digraph, run the steps in
   topological order (rejecting cycles), threading each step's outputs into an
   environment that later steps and the workflow outputs read from."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ubergraph.core :as uber]
            [ubergraph.alg :as alg]
            [fleur.command-line-tool :as clt]
            [fleur.expression :as expr]
            [fleur.preprocess :as pre]
            [fleur.process :as process]))

;;; ---------------------------------------------------------------------------
;;; Normalization: CWL allows map (id -> spec) and list ({:id ...}) forms
;;; ---------------------------------------------------------------------------

(defn- id-map
  "Normalize a CWL map-or-list of identified things into {id-keyword spec}.
   A bare (non-map) spec value is treated as a `:type` shorthand."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]]
                                    [(keyword (name k)) (if (map? v) v {:type v})])
                                  x))
    (sequential? x) (into {} (map (fn [m] [(keyword (name (:id m))) (dissoc m :id)]) x))
    :else           {}))

(defn- normalize-in
  "Normalize a step's `in` into {input-id {:source .. :default .. :valueFrom ..}}.
   Shorthands: `param: source` and `param: [s1 s2]` become `{:source ...}`."
  [in]
  (cond
    (map? in)        (into {} (map (fn [[k v]]
                                     [(keyword (name k))
                                      (cond
                                        (map? v)        v
                                        (sequential? v) {:source (vec v)}
                                        :else           {:source v})])
                                   in))
    (sequential? in) (into {} (map (fn [m] [(keyword (name (:id m))) (dissoc m :id)]) in))
    :else            {}))

(defn- normalize-out
  "Normalize a step's `out` into a vector of output-id keywords."
  [out]
  (mapv (fn [o] (keyword (name (if (map? o) (:id o) o)))) out))

(defn- normalize-steps
  [steps]
  (letfn [(norm [m] (-> m (update :in normalize-in) (update :out normalize-out)))]
    (cond
      (map? steps)        (into {} (map (fn [[k v]] [(keyword (name k)) (norm v)]) steps))
      (sequential? steps) (into {} (map (fn [m] [(keyword (name (:id m))) (norm (dissoc m :id))]) steps))
      :else               {})))

;;; ---------------------------------------------------------------------------
;;; Dependency graph
;;; ---------------------------------------------------------------------------

(defn- source->step
  "The upstream step id referenced by a source string (`stepid/outputid`), or
   nil when the source is a workflow input."
  [src]
  (when (string? src)
    (let [i (.indexOf src "/")]
      (when (pos? i) (keyword (subs src 0 i))))))

(defn- step-sources [step]
  (mapcat (fn [[_ spec]]
            (let [s (:source spec)]
              (cond (vector? s) s (some? s) [s] :else nil)))
          (:in step)))

(defn dependency-graph
  "An ubergraph digraph over the (normalized) steps, with an edge from each
   upstream step to the steps that consume its outputs. Suitable for
   `ubergraph.alg/topsort` and `ubergraph.core/viz-graph`."
  [steps]
  (let [g (apply uber/digraph (keys steps))]
    (reduce (fn [g [sid step]]
              (reduce (fn [g src]
                        (if-let [dep (source->step src)]
                          (uber/add-directed-edges g [dep sid])
                          g))
                      g
                      (step-sources step)))
            g
            steps)))

(defn- step-order
  "Topological order of the step ids, or throw if the workflow has a cycle."
  [steps]
  (or (alg/topsort (dependency-graph steps))
      (throw (ex-info "Workflow dependency graph has a cycle" {:steps (keys steps)}))))

;;; ---------------------------------------------------------------------------
;;; Execution
;;; ---------------------------------------------------------------------------

(defn- seed-environment
  "Seed the environment with workflow input values, keyed by input id string:
   the provided job value, else the declared default."
  [inputs provided]
  (into {} (map (fn [[iid spec]]
                  [(name iid) (if (contains? provided iid) (get provided iid) (:default spec))])
                inputs)))

(defn- resolve-source [env src]
  (if (vector? src) (mapv #(get env %) src) (get env src)))

(defn- resolve-step-inputs
  "Resolve a step's inputs into a job map {input-id value}: pull each `source`
   from the environment (falling back to `default`), then apply any `valueFrom`
   with `self` bound to the value and `inputs` to the resolved job map."
  [workflow step env]
  (let [base (into {} (for [[inp-id spec] (:in step)]
                        [inp-id (let [v (when (:source spec) (resolve-source env (:source spec)))]
                                  (if (and (nil? v) (contains? spec :default)) (:default spec) v))]))
        js? (clt/inline-javascript? workflow)]
    (into {} (for [[inp-id spec] (:in step)]
               [inp-id (if (:valueFrom spec)
                         (expr/evaluate (:valueFrom spec)
                                        {:inputs base :self (get base inp-id) :runtime {}}
                                        {:js? js?})
                         (get base inp-id))]))))

(defn- run-ref->path
  "Resolve a step `run` string reference to a filesystem path: strip a `file:`
   URI scheme (cwljava resolves `run` to `file://` URIs), and resolve relative
   references against `basedir`."
  [run basedir]
  (let [p (cond
            (str/starts-with? run "file://") (subs run (count "file://"))
            (str/starts-with? run "file:")   (subs run (count "file:"))
            :else                            run)]
    (if (str/starts-with? p "/") p (str (io/file basedir p)))))

(defn- step-process
  "The process a step runs: an inline process map, or a CWL file referenced by
   `run` (loaded and preprocessed via `fleur.preprocess`). `opts` may carry a
   `:backend` for preprocessing referenced files."
  [step basedir opts]
  (let [run (:run step)]
    (cond
      (map? run)    run
      (string? run) (pre/preprocess-file (run-ref->path run basedir)
                                         (select-keys opts [:backend]))
      :else         (throw (ex-info "Step :run must be a process map or a file path"
                                    {:run run})))))

(defn- req->class-map
  "Normalize a requirements/hints collection (list-of-maps or map-by-class) into
   {class-keyword spec}."
  [reqs]
  (cond
    (map? reqs)        (into {} (map (fn [[k v]] [(keyword (name k)) (or v {})]) reqs))
    (sequential? reqs) (into {} (map (fn [m] [(keyword (name (:class m))) (dissoc m :class)]) reqs))
    :else              {}))

(defn- inherit-requirements
  "Propagate the workflow's requirements onto a step's process, with the
   process's own requirements taking precedence (CWL requirement inheritance)."
  [workflow tool]
  (let [merged (merge (req->class-map (:requirements workflow))
                      (req->class-map (:requirements tool)))]
    (cond-> tool
      (seq merged) (assoc :requirements merged))))

(defn run
  "Run a Workflow against `provided-inputs`, returning it with `:boundOutputs`.

   Steps run in topological dependency order; each step's declared outputs are
   stored in the environment under `\"stepid/outputid\"` for downstream steps and
   the workflow outputs (`outputSource`) to consume. `opts` (e.g. `:basedir`) are
   passed through to each step's process runner."
  ([workflow provided-inputs] (run workflow provided-inputs {}))
  ([workflow provided-inputs {:keys [basedir] :as opts}]
   (let [basedir (or basedir (System/getProperty "user.dir"))
         inputs (id-map (:inputs workflow))
         outputs (id-map (:outputs workflow))
         steps (normalize-steps (:steps workflow))
         env0 (seed-environment inputs provided-inputs)
         env (reduce (fn [env sid]
                       (let [step (get steps sid)
                             tool (inherit-requirements workflow (step-process step basedir opts))
                             job (resolve-step-inputs workflow step env)
                             result (process/run tool job opts)
                             outs (:boundOutputs result)]
                         (reduce (fn [env out-id]
                                   (assoc env (str (name sid) "/" (name out-id))
                                          (get outs out-id)))
                                 env
                                 (:out step))))
                     env0
                     (step-order steps))
         bound (into {} (for [[oid ospec] outputs]
                          [oid (resolve-source env (:outputSource ospec))]))]
     (assoc workflow :boundOutputs bound))))
