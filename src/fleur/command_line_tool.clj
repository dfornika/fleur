(ns fleur.command-line-tool
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [fleur.expression :as expr]))

(defn assoc-input-with-value
  "Associate a value with a specific input in the tool."
  [tool [input-key input]]
  (assoc-in tool [:inputs input-key :value] input))

(defn assoc-inputs-with-values
  "Associate provided input values with the tool's inputs."
  [command-line-tool provided-inputs]
  (reduce assoc-input-with-value command-line-tool provided-inputs))

(defn assoc-default-if-exists
  "If a :default is provided, assoc is as the :value. Otherwise return
   the input unmodified"
  [input]
  (if (:default input)
    (assoc input :value (:default input))
    input))

(defn assoc-inputs-with-default-values
  "For each input, assoc any :default value as the :value"
  [command-line-tool]
  (let [tool-inputs (:inputs command-line-tool)]
    (update command-line-tool :inputs
            (fn [inputs]
              (into {} (mapv (fn [[input-key input]]
                               [input-key (assoc-default-if-exists input)]) inputs))))))

(defn input-sorter
  "Sort inputs by their :position in the :inputBinding."
  [[_ input]]
  (get-in input [:inputBinding :position] 0)) ; Default to 0 if no position is specified

(defn sort-inputs
  "Sort the tool's inputs by their :position."
  [inputs]
  (sort-by input-sorter inputs))

(defn input-type
  "Return the CWL type of an input/parameter as a keyword, tolerating both
   the plain-string form (\"File\") and the parsed keyword form (:File)."
  [spec]
  (let [t (:type spec)]
    (cond
      (keyword? t) t
      (string? t)  (keyword t)
      :else        nil)))

(defn- file-or-dir-path
  "Reduce a File/Directory value to its :path, passing other values through."
  [v]
  (if (map? v) (:path v) v))

(defn binding-tokens
  "Produce the list of command-line tokens for a single value, following the
   CWL type-to-argument and CommandLineBinding rules (prefix, separate,
   itemSeparator). Returns a (possibly empty) vector of string tokens."
  [type-kw binding value]
  (let [prefix (:prefix binding)
        separate (get binding :separate true)
        item-sep (:itemSeparator binding)
        emit (fn [s]
               (cond
                 (nil? prefix) [s]
                 separate      [prefix s]
                 :else         [(str prefix s)]))]
    (cond
      ;; null: add nothing
      (nil? value) []

      ;; boolean: if true add the prefix, if false add nothing
      (= type-kw :boolean)
      (if value (if prefix [prefix] []) [])

      ;; File / Directory: use the path
      (contains? #{:File :Directory} type-kw)
      (emit (str (file-or-dir-path value)))

      ;; array: join with itemSeparator, or add prefix then each element
      (sequential? value)
      (let [elems (map (comp str file-or-dir-path) value)]
        (cond
          (empty? elems) []
          item-sep       (emit (str/join item-sep elems))
          :else          (into (if prefix [prefix] []) elems)))

      ;; string / number / everything else
      :else (emit (str value)))))

(defn inline-javascript?
  "True if the tool enables InlineJavascriptRequirement via requirements or
   hints (supporting both the list-of-maps and map-keyed-by-class shapes)."
  [tool]
  (let [names (mapcat (fn [reqs]
                        (cond
                          (map? reqs)        (map name (keys reqs))
                          (sequential? reqs) (keep #(some-> (:class %) name) reqs)
                          :else              nil))
                      [(:requirements tool) (:hints tool)])]
    (boolean (some #{"InlineJavascriptRequirement"} names))))

(defn evaluation-context
  "Build an expression-evaluation context from a tool and a `runtime` map.
   `:inputs` maps each input id to its current value; `:runtime` carries
   runtime.* values (outdir, tmpdir, cores, ram, ...)."
  [tool runtime]
  {:inputs (into {} (map (fn [[k input]] [k (:value input)]) (:inputs tool)))
   :runtime (or runtime {})})

(defn- eval-when-context
  "Evaluate `v` against `context` when a context is supplied; otherwise return
   `v` unchanged (expressions are emitted literally without a context)."
  [v context js?]
  (if context (expr/evaluate v context {:js? js?}) v))

(defn- input-binding-entry
  "Build a sortable command-line entry for an input, or nil if the input has
   no inputBinding (such inputs are not placed on the command line). When a
   context is supplied, an inputBinding :valueFrom is evaluated with `self`
   bound to the input's value."
  [context js? [input-key input]]
  (let [binding (:inputBinding input)]
    (when binding
      (let [value (if (and context (contains? binding :valueFrom))
                    (expr/evaluate (:valueFrom binding)
                                   (assoc context :self (:value input))
                                   {:js? js?})
                    (:value input))]
        {:sort-key [(get binding :position 0) 1 (name input-key)]
         :tokens (binding-tokens (input-type input) binding value)}))))

(defn- argument-entry
  "Build a sortable command-line entry for a CWL `arguments` element. Plain
   strings become a single token; CommandLineBinding maps honour :position,
   :prefix and :valueFrom. With a context, expressions are evaluated."
  [context js? i arg]
  (if (map? arg)
    {:sort-key [(get arg :position 0) 0 i]
     :tokens (binding-tokens :string arg (eval-when-context (:valueFrom arg) context js?))}
    {:sort-key [0 0 i]
     :tokens (let [v (eval-when-context arg context js?)]
               (if (nil? v) [] [(str v)]))}))

(defn build-command-line
  "Build the command line for a tool per the CWL algorithm: collect binding
   entries from `arguments` and inputs, sort them by [position, kind, name],
   render each to tokens, and prepend `baseCommand`.

   With the 2-arity form, `context` (see `evaluation-context`) enables
   evaluation of parameter references / expressions in `arguments` and
   `valueFrom`; JavaScript is used when the tool declares
   InlineJavascriptRequirement. The 1-arity form emits expressions literally."
  ([tool] (build-command-line tool nil))
  ([tool context]
   (let [js? (inline-javascript? tool)
         base-command (:baseCommand tool)
         base (cond
                (nil? base-command)        []
                (sequential? base-command) (vec base-command)
                :else                      [base-command])
         arg-entries (map-indexed (partial argument-entry context js?) (:arguments tool))
         input-entries (keep (partial input-binding-entry context js?) (:inputs tool))
         ordered (sort-by :sort-key (concat arg-entries input-entries))
         command-line-elements (concat base (mapcat :tokens ordered))]
     (assoc tool :commandLine command-line-elements))))

(defn execute
  ""
  [tool]
  (let [command-line-str (:commandLine tool)
        execution-result (apply shell/sh command-line-str)]
    (assoc tool :executionResult execution-result)))

(defn glob-pattern-to-regex
  "Convert a simple glob pattern to a regex pattern."
  [pattern]
  (-> pattern
      (str/replace "." "\\.")
      (str/replace "*" ".*")
      (str/replace "?" ".")
      re-pattern))

(defn glob-files
  "Find files matching a glob pattern in the given directory."
  [directory pattern]
  (let [dir-file (io/file directory)
        pattern-regex (glob-pattern-to-regex pattern)]
    (->> dir-file
         .listFiles
         (filter #(.isFile %))
         (map #(.getPath %))
         (filter #(re-matches pattern-regex (.getName (io/file %)))))))

(defn bind-outputs
  "Bind outputs by collecting files matching glob patterns in outputBinding."
  [tool]
  (let [outputs (:outputs tool)
        working-dir (System/getProperty "user.dir")]
    (if outputs
      (let [bound-outputs
            (into {}
                  (for [[output-key output-spec] outputs]
                    (let [output-binding (:outputBinding output-spec)
                          glob-pattern (:glob output-binding)]
                      (if glob-pattern
                        (let [matching-files (glob-files working-dir glob-pattern)
                              output-type (:type output-spec)]
                          [output-key
                           (cond
                             (= output-type "File")
                             (if (seq matching-files)
                               {:class "File"
                                :path (first matching-files)
                                :basename (-> matching-files first io/file .getName)}
                               nil)

                             (= output-type "File[]")
                             (mapv (fn [file-path]
                                     {:class "File"
                                      :path file-path
                                      :basename (-> file-path io/file .getName)})
                                   matching-files)

                             :else
                             {:error (str "Unsupported output type: " output-type)})])
                        [output-key {:error "No glob pattern specified"}]))))]
        (assoc tool :boundOutputs bound-outputs))
      tool)))


