(ns fleur.command-line-tool
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

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

(defn format-input-value
  ""
  [input]
  (let [input-type (:keyword (:type input))]
    (cond
      (= input-type :string) (update input :value #(str/join [\" % \"]))
      (= input-type :File) (get-in input [:value :path])
      (= input-type :Directory) (get-in input [:value :path])
      :else (identity input))))

(defn format-input-values
  ""
  [command-line-tool]
  (update command-line-tool :inputs
          (fn [inputs]
            (into {} (mapv (fn [[input-key input]]
                             [input-key (format-input-value input)]) inputs)))))

(defn build-command-line
  "Take a command-line tool, and
   build the command-line to be run."
  [tool]
  (let [base-command (:baseCommand tool)
        tool-inputs (:inputs tool)
        sorted-tool-inputs (sort-inputs tool-inputs)
        tool-with-formatted-inputs (format-input-values tool)
        command-line-elements (concat [base-command] (map :value (vals (:inputs tool-with-formatted-inputs))))]
    (assoc tool :commandLine command-line-elements)))

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


