(ns fleur.preprocess
  "CWL document preprocessing.

   Schema-salad preprocessing is a large spec (`$import`/`$include`, `$graph`,
   identifier and link resolution, type-DSL expansion, validation). Rather than
   reimplement all of it, this namespace defines a small, backend-swappable API
   and implements a pragmatic pure-Clojure subset that covers the common cases
   for single-/lightly-modular documents.

   Backends (`:backend` option):
   - `:clojure` (default) — the native subset here.
   - `:schema-salad-tool` — shell out to schema-salad-tool for full fidelity
     (see `fleur.schema-salad`); requires that external tool.
   - `:cwljava` (future) — the generated Java loader.

   Implemented today: **type-DSL expansion** (`int?`, `File[]`, the `param: int`
   shorthand) and map/list-form normalization of `inputs`/`outputs`. Not yet
   implemented natively: `$import`/`$include` and `$graph` (these throw a clear
   error suggesting the `:schema-salad-tool` backend)."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [fleur.schema-salad :as schema-salad]))

(declare expand-types)

;;; ---------------------------------------------------------------------------
;;; Type DSL expansion
;;; ---------------------------------------------------------------------------

(defn expand-type-string
  "Expand a CWL type-DSL string into its full form:
   `int?`  -> [\"null\" \"int\"], `int[]` -> {:type \"array\" :items \"int\"}
   (stacked suffixes are handled outermost-first)."
  [s]
  (cond
    (str/ends-with? s "?")  ["null" (expand-type-string (subs s 0 (dec (count s))))]
    (str/ends-with? s "[]") {:type "array" :items (expand-type-string (subs s 0 (- (count s) 2)))}
    :else                   s))

(defn expand-type
  "Recursively expand a CWL type: DSL strings, unions (vectors), and the nested
   types of array (`:items`) and record (`:fields`) definitions."
  [t]
  (cond
    (string? t)     (expand-type-string t)
    (sequential? t) (mapv expand-type t)
    (map? t)        (case (some-> (:type t) name)
                      "array"  (update t :items expand-type)
                      "record" (update t :fields
                                       (fn [fields]
                                         (cond
                                           (map? fields)        (into {} (map (fn [[k v]] [k (cond-> v (:type v) (update :type expand-type))]) fields))
                                           (sequential? fields) (mapv #(cond-> % (:type %) (update :type expand-type)) fields)
                                           :else                fields)))
                      t)
    :else           t))

;;; ---------------------------------------------------------------------------
;;; inputs / outputs normalization
;;; ---------------------------------------------------------------------------

(defn- param-map
  "Normalize a CWL inputs/outputs collection (map or list form) into
   {id-keyword spec-map}, treating a bare type value as a `:type` shorthand."
  [params]
  (cond
    (map? params)        (into {} (map (fn [[k v]]
                                         [(keyword (name k)) (if (map? v) v {:type v})])
                                       params))
    (sequential? params) (into {} (map (fn [m] [(keyword (name (:id m))) (dissoc m :id)]) params))
    :else                {}))

(defn- expand-params [params]
  (into {} (map (fn [[k spec]] [k (cond-> spec (:type spec) (update :type expand-type))])
                (param-map params))))

(defn- expand-step [step]
  (cond-> step
    (map? (:run step)) (update :run expand-types)))

(defn expand-types
  "Normalize `inputs`/`outputs` to map form and expand every type DSL in a CWL
   process document, recursing into workflow steps' inline `run` processes."
  [doc]
  (cond-> doc
    (:inputs doc)  (update :inputs expand-params)
    (:outputs doc) (update :outputs expand-params)
    (:steps doc)   (update :steps
                           (fn [steps]
                             (cond
                               (map? steps)        (into {} (map (fn [[k v]] [k (expand-step v)]) steps))
                               (sequential? steps) (mapv expand-step steps)
                               :else               steps)))))

;;; ---------------------------------------------------------------------------
;;; Not-yet-implemented passes (fail loudly rather than mis-handle)
;;; ---------------------------------------------------------------------------

(defn- doc-str [doc] (pr-str (select-keys doc [:id :class])))

(defn resolve-imports
  "Resolve `$import`/`$include` directives. Not yet implemented in the native
   backend: throws if any are present so documents aren't silently mishandled."
  [doc _basedir]
  (when (some #(and (map? %) (or (contains? % :$import) (contains? % :$include)))
              (tree-seq coll? seq doc))
    (throw (ex-info "$import/$include not supported by the :clojure backend yet; use :backend :schema-salad-tool"
                    {:doc (doc-str doc)})))
  doc)

(defn select-graph
  "Handle a `$graph` document (multiple processes in one file). Not yet
   implemented natively: throws if `$graph` is present."
  [doc]
  (if (contains? doc :$graph)
    (throw (ex-info "$graph not supported by the :clojure backend yet; use :backend :schema-salad-tool"
                    {:doc (doc-str doc)}))
    doc))

;;; ---------------------------------------------------------------------------
;;; cwljava backend (optional; requires the :cwljava alias / classpath)
;;;
;;; cwljava is the schema-salad-generated Java SDK. It performs full document
;;; loading/preprocessing and returns a typed Java object graph, which we adapt
;;; back into Fleur's plain keyword-maps. Loaded reflectively so this namespace
;;; never hard-depends on cwljava being on the classpath.
;;; ---------------------------------------------------------------------------

(declare java->clj)

(defn- cwljava-object? [x]
  (and (some? x) (.startsWith (.getName (class x)) "org.commonwl.cwlsdk")))

(defn- enum->cwl
  "A generated CWL enum's symbol (e.g. \"CommandLineTool\", \"File\"). The symbol
   is stored in a private `docVal` field; fall back to the Java constant name."
  [x]
  (or (try (let [f (.getDeclaredField (class x) "docVal")]
             (.setAccessible f true)
             (.get f x))
           (catch Throwable _ nil))
      (.name ^Enum x)))

(defn- bean->clj
  "Convert a cwljava object to a map: drop Java/loader plumbing, prune keys whose
   value is absent (so Fleur's binding defaults apply), and rename the generated
   `class_` field back to the CWL `class`."
  [x]
  (let [m (reduce (fn [m [k v]]
                    (let [cv (java->clj v)]
                      (if (nil? cv) m (assoc m k cv))))
                  {}
                  (dissoc (bean x) :class :loadingOptions :extensionFields))]
    (if (contains? m :class_)
      (-> m (assoc :class (:class_ m)) (dissoc :class_))
      m)))

(defn- java->clj
  "Recursively convert a cwljava/Java value into Clojure data: unwrap Optionals,
   Maps -> maps, Lists -> vectors, enums -> their CWL symbol, cwljava objects ->
   maps; scalars pass through."
  [x]
  (cond
    (nil? x)                          nil
    (instance? java.util.Optional x)  (java->clj (.orElse ^java.util.Optional x nil))
    (instance? java.util.Map x)       (reduce (fn [m [k v]] (assoc m (keyword (str k)) (java->clj v))) {} x)
    (instance? java.util.List x)      (mapv java->clj x)
    (instance? java.lang.Enum x)      (enum->cwl x)
    (cwljava-object? x)               (bean->clj x)
    :else                             x))

(defn- short-id
  "Reduce a cwljava-resolved identifier URI to its short name (the fragment after
   the last `#`, then the last path segment)."
  [id]
  (when id
    (-> (str id) (str/split #"#") last (str/split #"/") last)))

(defn- shorten-ids
  "Rewrite `:id` fields to short names, so the adapted document matches Fleur's
   short-identifier conventions."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]]
                                    [k (if (and (= k :id) (string? v))
                                         (short-id v)
                                         (shorten-ids v))])
                                  x))
    (sequential? x) (mapv shorten-ids x)
    :else           x))

(defn cwljava-load-file
  "Load and preprocess a CWL file with cwljava (via reflection), returning a
   Fleur-style keyword-map. Requires cwljava on the classpath (`:cwljava` alias)."
  [f]
  (let [rl (try (Class/forName "org.commonwl.cwlsdk.cwl1_2.utils.RootLoader")
                (catch ClassNotFoundException _
                  (throw (ex-info "cwljava is not on the classpath — enable the :cwljava alias (e.g. `clojure -X:test:cwljava`)"
                                  {:backend :cwljava}))))
        m (.getMethod rl "loadDocument" (into-array Class [java.io.File]))]
    (-> (.invoke m nil (object-array [(io/file f)]))
        java->clj
        shorten-ids
        ;; normalize inputs/outputs to Fleur's map form (types are already
        ;; resolved by cwljava, so this is just list->map + idempotent expansion)
        expand-types)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn preprocess
  "Preprocess an already-parsed CWL document (a Clojure map) into a normalized
   form ready for execution. Options:
   - `:backend`  `:clojure` (default) or `:schema-salad-tool`
   - `:basedir`  base directory for (future) `$import`/`$include` resolution

   The `:schema-salad-tool` backend operates on files, not parsed maps — use
   `preprocess-file` for it."
  ([doc] (preprocess doc {}))
  ([doc {:keys [backend basedir] :or {backend :clojure}}]
   (case backend
     :clojure (-> doc (resolve-imports basedir) select-graph expand-types)
     (:schema-salad-tool :cwljava)
     (throw (ex-info (str "The " backend " backend works on files; use preprocess-file")
                     {:backend backend}))
     (throw (ex-info (str "Unknown preprocessing backend: " backend) {:backend backend})))))

(defn preprocess-file
  "Read and preprocess a CWL file. With `:backend :clojure` (default), parses the
   YAML and applies the native subset (basedir defaults to the file's directory).
   With `:backend :schema-salad-tool`, delegates to schema-salad-tool for full
   preprocessing."
  ([f] (preprocess-file f {}))
  ([f {:keys [backend] :or {backend :clojure} :as opts}]
   (case backend
     :clojure (let [basedir (or (:basedir opts) (some-> (io/file f) .getAbsoluteFile .getParent))]
                (preprocess (yaml/parse-string (slurp f)) (assoc opts :basedir basedir)))
     :schema-salad-tool (schema-salad/preprocess-via-shell f)
     :cwljava (cwljava-load-file f)
     (throw (ex-info (str "Unknown preprocessing backend: " backend) {:backend backend})))))
