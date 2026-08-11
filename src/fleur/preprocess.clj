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

(defn- after-hash
  "The fragment of a resolved identifier URI after the last `#` (or the whole
   string when there is no `#`)."
  [s]
  (-> (str s) (str/split #"#") last))

(defn- short-id
  "A cwljava-resolved identifier reduced to its leaf name (fragment, then last
   path segment): e.g. `file:...#main/step/out` -> `out`, `#x` -> `x`."
  [id]
  (-> (after-hash id) (str/split #"/") last))

(defn- shorten-ids
  "Rewrite cwljava's resolved identifier URIs to Fleur's short conventions:
   `:id` -> leaf name; `:source`/`:outputSource` -> `stepid/outputid` (or a
   workflow input id, keeping that structure); a step's `:out` entries -> their
   leaf output names. Other values recurse."
  [x]
  (letfn [(ref-> [v] (cond
                       (string? v)     (after-hash v)
                       (sequential? v) (mapv #(if (string? %) (after-hash %) %) v)
                       :else           v))
          (leaf [v] (if (string? v) (short-id v) v))]
    (cond
      (map? x)        (reduce-kv
                       (fn [m k v]
                         (assoc m k
                                (case k
                                  :id                     (leaf v)
                                  (:source :outputSource) (ref-> v)
                                  :out                    (if (sequential? v) (mapv leaf v) v)
                                  (shorten-ids v))))
                       {}
                       x)
      (sequential? x) (mapv shorten-ids x)
      :else           x)))

(defn- clj->java
  "Convert a Clojure document into the java.util structures cwljava's
   loadDocument(Map) expects (keyword keys -> field-name strings)."
  [x]
  (cond
    (map? x)        (let [m (java.util.LinkedHashMap.)]
                      (doseq [[k v] x] (.put m (name k) (clj->java v)))
                      m)
    (sequential? x) (let [l (java.util.ArrayList.)]
                      (doseq [v x] (.add l (clj->java v)))
                      l)
    (keyword? x)    (name x)
    :else           x))

(defn- cwljava-load
  "Invoke cwljava's RootLoader/loadDocument (reflectively) on `arg` of type
   `arg-class`, then adapt the result into a Fleur-style keyword-map: convert the
   object graph, shorten resolved ids, and normalize inputs/outputs to map form
   (types are already resolved, so `expand-types` is just list->map here)."
  [arg-class arg]
  (let [rl (try (Class/forName "org.commonwl.cwlsdk.cwl1_2.utils.RootLoader")
                (catch ClassNotFoundException _
                  (throw (ex-info "cwljava is not on the classpath" {:backend :cwljava}))))
        m (.getMethod rl "loadDocument" (into-array Class [arg-class]))]
    (-> (.invoke m nil (object-array [arg]))
        java->clj
        shorten-ids
        expand-types)))

(defn cwljava-load-file
  "Load and preprocess a CWL file with cwljava, returning a Fleur-style map."
  [f]
  (cwljava-load java.io.File (io/file f)))

(defn cwljava-load-map
  "Preprocess an already-parsed CWL document map with cwljava."
  [doc]
  (cwljava-load java.util.Map (clj->java doc)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(def default-backend
  "The preprocessing backend used when none is specified. `:cwljava` (the
   schema-salad-generated Java loader) does full-fidelity loading; the native
   `:clojure` subset and the `:schema-salad-tool` shell remain available."
  :cwljava)

(defn preprocess
  "Preprocess an already-parsed CWL document (a Clojure map) into a normalized
   form ready for execution. Options:
   - `:backend`  `:cwljava` (default), `:clojure`, or `:schema-salad-tool`
   - `:basedir`  base directory for (future) `$import`/`$include` resolution
     (`:clojure` backend only)

   The `:schema-salad-tool` backend operates on files, not parsed maps — use
   `preprocess-file` for it."
  ([doc] (preprocess doc {}))
  ([doc {:keys [backend basedir] :or {backend default-backend}}]
   (case backend
     :cwljava (cwljava-load-map doc)
     :clojure (-> doc (resolve-imports basedir) select-graph expand-types)
     :schema-salad-tool
     (throw (ex-info "The :schema-salad-tool backend works on files; use preprocess-file"
                     {:backend backend}))
     (throw (ex-info (str "Unknown preprocessing backend: " backend) {:backend backend})))))

(defn preprocess-file
  "Read and preprocess a CWL file. With `:backend :cwljava` (default), cwljava
   loads and fully preprocesses it. With `:backend :clojure`, parses the YAML and
   applies the native subset (basedir defaults to the file's directory). With
   `:backend :schema-salad-tool`, delegates to schema-salad-tool."
  ([f] (preprocess-file f {}))
  ([f {:keys [backend] :or {backend default-backend} :as opts}]
   (case backend
     :cwljava (cwljava-load-file f)
     :clojure (let [basedir (or (:basedir opts) (some-> (io/file f) .getAbsoluteFile .getParent))]
                (preprocess (yaml/parse-string (slurp f)) (assoc opts :backend :clojure :basedir basedir)))
     :schema-salad-tool (schema-salad/preprocess-via-shell f)
     (throw (ex-info (str "Unknown preprocessing backend: " backend) {:backend backend})))))
