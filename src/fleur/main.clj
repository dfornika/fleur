(ns fleur.main
  "Command-line entry point for running CWL documents, following the `cwl-runner`
   convention:

     cwl-runner [options] <document.cwl> [<job.json|job.yml>]

   The CWL process is preprocessed (cwljava by default) and run; its bound
   outputs are written to stdout as a JSON object. Inputs come from a job file
   (JSON or YAML); with no job file, declared defaults are used."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.data.json :as json]
            [clj-yaml.core :as yaml]
            [fleur.process :as process])
  (:gen-class))

(def version "0.1.0")

(def ^:private cli-options
  [["-o" "--outdir DIR" "Output directory for the process (default: a temp dir)"]
   [nil  "--backend NAME" "Preprocessing backend: cwljava (default), clojure, or schema-salad-tool"
    :parse-fn keyword
    :validate [#{:cwljava :clojure :schema-salad-tool} "must be cwljava, clojure, or schema-salad-tool"]]
   ["-h" "--help" "Show this help and exit"]
   ["-v" "--version" "Show version and exit"]])

(defn- usage [summary]
  (str/join
   \newline
   ["Fleur - a Common Workflow Language runner"
    ""
    "Usage: cwl-runner [options] <document.cwl> [<job.json|job.yml>]"
    ""
    "Options:"
    summary
    ""
    "The process is run and its output object is printed to stdout as JSON."]))

(defn load-job
  "Read a CWL input job file (JSON or YAML) into a keyword-keyed map. Returns an
   empty map when `job-file` is nil (declared defaults are then used)."
  [job-file]
  (if job-file
    (yaml/parse-string (slurp job-file))   ; clj-yaml also parses JSON
    {}))

(defn run-document
  "Run the CWL `cwl-file` against the (optional) `job-file`, returning the bound
   outputs. `opts` are passed through to `fleur.process/run-file`."
  ([cwl-file job-file] (run-document cwl-file job-file {}))
  ([cwl-file job-file opts]
   (:boundOutputs (process/run-file cwl-file (load-job job-file) opts))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)    (do (println (usage summary)) (System/exit 0))
      (:version options) (do (println "cwl-runner (Fleur)" version) (System/exit 0))
      errors             (binding [*out* *err*]
                           (doseq [e errors] (println e))
                           (println "\n" (usage summary))
                           (System/exit 2))
      (empty? arguments) (binding [*out* *err*]
                           (println "error: no CWL document given\n")
                           (println (usage summary))
                           (System/exit 2))
      :else
      (let [[cwl-file job-file] arguments
            opts (cond-> {}
                   (:outdir options)  (assoc :outdir (:outdir options))
                   (:backend options) (assoc :backend (:backend options)))]
        (try
          (println (json/write-str (run-document cwl-file job-file opts)))
          (flush)
          (System/exit 0)
          (catch Throwable e
            (binding [*out* *err*] (println "cwl-runner error:" (.getMessage e)))
            (System/exit 1)))))))
