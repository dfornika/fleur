(ns fleur.command-line-tool
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [fleur.expression :as expr]
            [fleur.runtime :as rt]
            [fleur.staging :as stg]
            [fleur.docker :as docker]))

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

(defn- requirement
  "The spec for requirement/hint `class-name` (a string), searching
   `:requirements` first then `:hints`, and supporting both the list-of-maps and
   map-keyed-by-class shapes. Returns nil when absent."
  [tool class-name]
  (some (fn [reqs]
          (cond
            (map? reqs)        (get reqs (keyword class-name))
            (sequential? reqs) (some #(when (= class-name (some-> (:class %) name))
                                        (dissoc % :class))
                                     reqs)
            :else              nil))
        [(:requirements tool) (:hints tool)]))

(defn env-vars
  "Environment variables defined by an `EnvVarRequirement`, evaluated against
   `context` (values may be CWL expressions). `envDef` may be a map
   `{VAR value}` or a list `[{:envName VAR :envValue value}]`. Returns
   `{\"VAR\" \"value\"}` (string keys/values), or nil when the requirement is absent."
  [tool context js?]
  (when-let [req (requirement tool "EnvVarRequirement")]
    (let [envdef (:envDef req)
          ev     (fn [v] (str (if context (expr/evaluate v context {:js? js?}) v)))]
      (cond
        (map? envdef)        (into {} (map (fn [[k v]] [(name k) (ev v)]) envdef))
        (sequential? envdef) (into {} (map (fn [m] [(name (:envName m)) (ev (:envValue m))]) envdef))
        :else                {}))))

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

(defn- as-path
  "A File/Directory value's :path, or the value itself if already a path string."
  [v]
  (if (map? v) (:path v) v))

(defn execute
  "Run the tool's `:commandLine`. With a `context`, `stdin`/`stdout`/`stderr`
   are evaluated as expressions; the process runs in `runtime.outdir` (when
   present) with stdin redirected from the named file, and captured stdout/stderr
   written to the named files under outdir. The result is stored under
   `:executionResult`."
  ([tool] (execute tool nil))
  ([tool context]
   (let [js? (inline-javascript? tool)
         cmd (:commandLine tool)
         outdir (get-in context [:runtime :outdir])
         ev (fn [v] (when v (expr/evaluate v context {:js? js?})))
         stdin (some-> (ev (:stdin tool)) as-path)
         stdout-name (ev (:stdout tool))
         stderr-name (ev (:stderr tool))
         ;; EnvVarRequirement: shell/sh's :env REPLACES the environment, so merge
         ;; the requirement's vars over the inherited parent environment.
         env (env-vars tool context js?)
         sh-args (cond-> (vec cmd)
                   stdin     (conj :in (io/file stdin))
                   (seq env) (conj :env (merge (into {} (System/getenv)) env))
                   outdir    (conj :dir outdir))
         result (apply shell/sh sh-args)]
     (when (and stdout-name outdir)
       (spit (io/file outdir stdout-name) (:out result)))
     (when (and stderr-name outdir)
       (spit (io/file outdir stderr-name) (:err result)))
     (assoc tool :executionResult result))))

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

(defn- file-object
  "A CWL File object for a path on disk."
  [path]
  {:class "File"
   :path path
   :basename (.getName (io/file path))})

(defn- strip-extensions
  "Remove `n` trailing extensions (text after the last dot) from `path`."
  [path n]
  (reduce (fn [p _]
            (let [i (.lastIndexOf p ".")]
              (if (pos? i) (subs p 0 i) p)))
          path
          (range n)))

(defn- secondary-file-paths
  "Resolve one secondaryFiles pattern against a primary File `value`.
   A pattern may be a CWL expression (evaluated with `self` = the primary File,
   yielding a path/File or a list of them) or a string suffix rule where each
   leading `^` strips one extension from the primary path before appending the
   remainder (e.g. \".bai\" -> path+\".bai\"; \"^.idx\" -> path-ext+\".idx\")."
  [pattern value context js?]
  (let [pattern (if (map? pattern) (:pattern pattern) pattern)]
    (cond
      (and context (expr/contains-expression? pattern))
      (let [result (expr/evaluate pattern (assoc context :self value) {:js? js?})]
        (->> (if (sequential? result) result [result])
             (map as-path)
             (remove nil?)))

      (string? pattern)
      (let [path (as-path value)
            carets (count (take-while #{\^} pattern))
            suffix (subs pattern carets)]
        [(str (strip-extensions path carets) suffix)])

      :else nil)))

(defn- attach-secondary-files
  "Attach existing secondaryFiles to a bound File `value` per `output-spec`."
  [value output-spec context js?]
  (if-let [patterns (:secondaryFiles output-spec)]
    (let [patterns (if (sequential? patterns) patterns [patterns])
          found (->> patterns
                     (mapcat #(secondary-file-paths % value context js?))
                     (filter #(.exists (io/file %)))
                     (mapv file-object))]
      (cond-> value (seq found) (assoc :secondaryFiles found)))
    value))

(defn- attach-format
  "Attach an evaluated `format` to a bound File `value` per `output-spec`."
  [value output-spec context js?]
  (if-let [fmt (:format output-spec)]
    (assoc value :format (if context (expr/evaluate fmt context {:js? js?}) fmt))
    value))

(defn- glob-patterns
  "Evaluate an outputBinding :glob into a seq of concrete pattern strings.
   `glob` may be a string, an expression, or a list thereof."
  [glob context js?]
  (->> (if (sequential? glob) glob [glob])
       (map #(if context (expr/evaluate % context {:js? js?}) %))
       (mapcat #(if (sequential? %) % [%]))
       (remove nil?)))

(defn- array-output-type?
  "True if an output type denotes an array of File (e.g. \"File[]\" or a parsed
   {:type \"array\" :items \"File\"})."
  [output-type]
  (or (= output-type "File[]")
      (and (map? output-type)
           (= "array" (some-> (:type output-type) name)))))

(defn bind-outputs
  "Bind outputs by collecting files matching outputBinding globs. With a
   `context`, `glob`/`secondaryFiles`/`format` expressions are evaluated and
   files are collected from `runtime.outdir`; otherwise the current working
   directory is used and globs are treated literally."
  ([tool] (bind-outputs tool nil))
  ([tool context]
   (let [outputs (:outputs tool)
         js? (inline-javascript? tool)
         working-dir (or (get-in context [:runtime :outdir])
                         (System/getProperty "user.dir"))]
     (if outputs
       (let [bound-outputs
             (into {}
                   (for [[output-key output-spec] outputs]
                     (let [binding (:outputBinding output-spec)
                           glob (:glob binding)]
                       (if glob
                         (let [matching-files (->> (glob-patterns glob context js?)
                                                   (mapcat #(glob-files working-dir %))
                                                   distinct)
                               output-type (:type output-spec)
                               decorate #(-> % (attach-secondary-files output-spec context js?)
                                             (attach-format output-spec context js?))
                               ;; loadContents attaches each globbed File's
                               ;; contents as `.contents` (visible to outputEval);
                               ;; a file over 64 KiB is a fatal error.
                               files (mapv (fn [p]
                                             (cond-> (file-object p)
                                               (:loadContents binding) (assoc :contents (stg/load-contents p))))
                                           matching-files)]
                           [output-key
                            (cond
                              ;; outputEval derives the output value from the glob
                              ;; result (bound to `self`); it may be any type.
                              (:outputEval binding)
                              (let [self (mapv decorate files)]
                                (if context
                                  (expr/evaluate (:outputEval binding) (assoc context :self self) {:js? js?})
                                  (:outputEval binding)))

                              (array-output-type? output-type)
                              (mapv decorate files)

                              (= output-type "File")
                              (when (seq files) (decorate (first files)))

                              :else
                              {:error (str "Unsupported output type: " output-type)})])
                         [output-key {:error "No glob pattern specified"}]))))]
         (assoc tool :boundOutputs bound-outputs))
       tool))))

(defn- run-docker
  "Run a dockerized tool: mount inputs read-only and outdir/tmpdir read-write,
   remap input paths to their container locations, build the command line with a
   container-relative runtime, and wrap it in `docker run`. stdout/stderr are
   captured and written to the host outdir (which is the mounted container
   outdir), so `bind-outputs` collects results from the host side."
  [tool req runtime {:keys [match-user? docker-user] :or {match-user? true} :as opts}]
  (let [js? (inline-javascript? tool)
        host-outdir (:outdir runtime)
        c-outdir (docker/container-outdir req)
        c-tmpdir docker/default-container-tmpdir
        {:keys [mounts path-map]} (docker/plan-mounts tool host-outdir (:tmpdir runtime)
                                                      c-outdir c-tmpdir)
        host-context (evaluation-context tool runtime)
        c-tool (docker/remap-tool-paths tool path-map)
        c-context (evaluation-context c-tool (assoc runtime :outdir c-outdir :tmpdir c-tmpdir))
        ;; stdin is piped into `docker run` from the host, so keep the host path
        stdin (some-> (when (:stdin tool) (expr/evaluate (:stdin tool) host-context {:js? js?}))
                      as-path)
        stdout-name (when (:stdout tool) (expr/evaluate (:stdout tool) c-context {:js? js?}))
        stderr-name (when (:stderr tool) (expr/evaluate (:stderr tool) c-context {:js? js?}))
        user (or docker-user (when match-user? (docker/current-user)))
        network (docker/network-arg tool c-context js?)
        image (docker/ensure-image! req)
        ;; InitialWorkDirRequirement stages onto the HOST outdir, so File/input
        ;; sources must resolve to host paths; runtime.* references, however, are
        ;; consumed inside the container, so use the container runtime there.
        iwdr-context (assoc host-context :runtime (:runtime c-context))]
    (stg/initial-work-dir! tool host-outdir iwdr-context js?)
    (let [built (build-command-line c-tool c-context)
          argv (docker/docker-run-argv image mounts c-outdir (:commandLine built)
                                       {:user user :network network})
          result (apply shell/sh (cond-> argv stdin (conj :in (io/file stdin))))]
      (when stdout-name (spit (io/file host-outdir stdout-name) (:out result)))
      (when stderr-name (spit (io/file host-outdir stderr-name) (:err result)))
      (-> built
          (assoc :commandLine argv :executionResult result)
          (bind-outputs host-context)))))

(defn run
  "End-to-end convenience: apply defaults and `provided-inputs`, resolve input
   File paths, build the `runtime` object and evaluation context, stage inputs,
   then build the command line, execute, and bind outputs. Tools declaring a
   `DockerRequirement` run inside a container (see `run-docker`).

   `opts` are passed to `fleur.runtime/make-runtime` (e.g. `{:outdir \"...\"}`)
   plus staging controls:
   - `:basedir`       base directory for resolving relative input paths
                      (default: the current working directory)
   - `:stage-inputs?` copy every input File/Directory into `runtime.outdir`
                      before running (default: false; paths stay absolute).
                      Ignored for dockerized tools, which mount inputs instead.
   - `:match-user?`   for dockerized tools, run the container as the invoking
                      user so outputs are owned by the caller (default: true).
   - `:docker-user`   explicit `docker run --user` value (overrides
                      `:match-user?`), e.g. \"0:0\" to run as root."
  ([tool provided-inputs] (run tool provided-inputs {}))
  ([tool provided-inputs {:keys [basedir stage-inputs?] :as opts}]
   (let [basedir (or basedir (System/getProperty "user.dir"))
         tool (-> tool
                  assoc-inputs-with-default-values
                  (assoc-inputs-with-values provided-inputs)
                  (stg/resolve-inputs basedir)
                  stg/load-contents-inputs)
         runtime (rt/make-runtime tool opts)
         req (docker/docker-requirement tool)]
     (if req
       (run-docker tool req runtime opts)
       (let [tool (cond-> tool
                    stage-inputs? (stg/stage-inputs! (:outdir runtime)))
             context (evaluation-context tool runtime)
             js? (inline-javascript? tool)]
         (stg/initial-work-dir! tool (:outdir runtime) context js?)
         (-> tool
             (build-command-line context)
             (execute context)
             (bind-outputs context)))))))


