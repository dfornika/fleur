(ns fleur.workflow
  "Execution of CWL `Workflow` processes (static-DAG; supports scatter/gather,
   but not conditional `when` yet).

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

(defn- leaf-keyword
  "The leaf name of a (possibly cwljava-scoped) identifier, as a keyword:
   `file:...#add/x` -> :x, `n` -> :n, `:x` -> :x (already-normalized keywords
   pass through)."
  [s]
  (-> (if (keyword? s) (name s) (str s))
      (str/split #"#") last (str/split #"/") last keyword))

(defn- normalize-scatter
  "Normalize a step's `scatter` (a single id or a list) into a vector of leaf
   input-id keywords."
  [scatter]
  (mapv leaf-keyword (if (sequential? scatter) scatter [scatter])))

(defn- normalize-steps
  [steps]
  (letfn [(norm [m] (-> m
                        (update :in normalize-in)
                        (update :out normalize-out)
                        (cond->
                         (:scatter m)       (update :scatter normalize-scatter)
                         (:scatterMethod m) (update :scatterMethod #(keyword (name %))))))]
    (cond
      (map? steps)        (into {} (map (fn [[k v]] [(keyword (name k)) (norm v)]) steps))
      (sequential? steps) (into {} (map (fn [m] [(keyword (name (:id m))) (norm (dissoc m :id))]) steps))
      :else               {})))

;;; ---------------------------------------------------------------------------
;;; Source canonicalization
;;; ---------------------------------------------------------------------------

(defn- canonical-source
  "Reduce a source ref to Fleur's form given the workflow's `step-ids`.

   cwljava scopes an inline sub-workflow's refs with the enclosing step id, so
   inside a sub-workflow `x` arrives as `inner/x` and `double/out` as
   `inner/double/out`. Fleur's convention is `stepid/outputid` (one slash) or a
   bare workflow-input id (no slash). We keep the last two segments when the
   second-to-last names a known step, else the last segment. Idempotent on refs
   that are already in Fleur form."
  [step-ids src]
  (if-not (string? src)
    src
    (let [segs (str/split src #"/")
          n    (count segs)]
      (if (and (>= n 2)
               (contains? step-ids (keyword (nth segs (- n 2)))))
        (str/join "/" (subvec segs (- n 2)))
        (peek segs)))))

(defn- canonicalize-sources
  "Rewrite every step `:in` `:source` and every output `:outputSource` in the
   (normalized) workflow to Fleur's canonical form (see `canonical-source`)."
  [steps outputs]
  (let [step-ids (set (keys steps))
        canon    (fn [s] (if (vector? s)
                           (mapv #(canonical-source step-ids %) s)
                           (canonical-source step-ids s)))]
    [(into {} (for [[sid step] steps]
                [sid (update step :in
                             (fn [in] (into {} (for [[iid spec] in]
                                                 [iid (cond-> spec
                                                        (:source spec) (update :source canon))]))))]))
     (into {} (for [[oid spec] outputs]
                [oid (cond-> spec
                       (:outputSource spec) (update :outputSource canon))]))]))

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

;;; ---------------------------------------------------------------------------
;;; Scatter / gather (CWL Workflow.yml "Scatter/gather")
;;; ---------------------------------------------------------------------------

(defn- cartesian
  "Cartesian product of `colls` (a seq of seqs) as a vector of combo vectors, in
   row-major order (the first collection varies slowest)."
  [colls]
  (reduce (fn [acc coll] (vec (for [a acc b coll] (conj a b))))
          [[]]
          colls))

(defn- assoc-combo
  "Assoc one element of each scattered input into `base-job` (a single scatter job)."
  [base-job scatter combo]
  (reduce (fn [j [p v]] (assoc j p v)) base-job (map vector scatter combo)))

(defn- nested-jobs
  "Build the nested job structure for `nested_crossproduct`: an N-deep nesting of
   vectors (one level per scattered input) whose leaves are job maps."
  [base-job scatter arrays]
  (if (empty? scatter)
    base-job
    (let [[p & ps] scatter
          [arr & arrs] arrays]
      (mapv (fn [e] (nested-jobs (assoc base-job p e) ps arrs)) arr))))

(defn- scatter-jobs
  "Decompose `base-job` into scatter jobs over the `scatter` input ids per
   `method`. Returns a flat vector of job maps (single input, `:dotproduct`,
   `:flat_crossproduct`) or a nested structure of them (`:nested_crossproduct`)."
  [base-job scatter method]
  (let [arrays (map #(get base-job %) scatter)]
    (if (= 1 (count scatter))
      ;; A single scattered input: methods are equivalent to a simple scatter.
      (mapv #(assoc base-job (first scatter) %) (first arrays))
      (case method
        :dotproduct
        (let [lens (map count arrays)]
          (when-not (apply = lens)
            (throw (ex-info "scatterMethod dotproduct requires equal-length arrays"
                            {:scatter scatter :lengths lens})))
          (mapv #(assoc-combo base-job scatter (mapv (fn [a] (nth a %)) arrays))
                (range (first lens))))

        :flat_crossproduct
        (mapv #(assoc-combo base-job scatter %) (cartesian arrays))

        :nested_crossproduct
        (nested-jobs base-job scatter arrays)

        (throw (ex-info "scatterMethod is required when scattering over multiple inputs"
                        {:scatter scatter :method method}))))))

(defn- run-scatter
  "Run `tool` once per scatter job and gather each of `out-ids` into an output
   array (nested for `:nested_crossproduct`), returning `{out-id gathered}`. If
   any scattered input is an empty array, all outputs are empty arrays and no
   jobs run (CWL scatter rule)."
  [tool base-job {:keys [scatter method out-ids opts]}]
  (let [arrays (map #(get base-job %) scatter)]
    (if (some empty? arrays)
      (into {} (map (fn [o] [o []]) out-ids))
      (let [run-leaf (fn run-leaf [node]
                       (if (map? node)
                         (:boundOutputs (process/run tool node opts))
                         (mapv run-leaf node)))
            results  (run-leaf (scatter-jobs base-job scatter method))
            gather   (fn gather [o node]
                       (if (map? node) (get node o) (mapv #(gather o %) node)))]
        (into {} (map (fn [o] [o (gather o results)]) out-ids))))))

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
         [steps outputs] (canonicalize-sources (normalize-steps (:steps workflow))
                                               (id-map (:outputs workflow)))
         env0 (seed-environment inputs provided-inputs)
         env (reduce (fn [env sid]
                       (let [step (get steps sid)
                             tool (inherit-requirements workflow (step-process step basedir opts))
                             job (resolve-step-inputs workflow step env)
                             outs (if-let [scatter (:scatter step)]
                                    (run-scatter tool job {:scatter scatter
                                                           :method (:scatterMethod step)
                                                           :out-ids (:out step)
                                                           :opts opts})
                                    (:boundOutputs (process/run tool job opts)))]
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
