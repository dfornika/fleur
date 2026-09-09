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
            [fleur.log :as log]
            [fleur.process :as process])
  (:gen-class))

(def version "0.1.0")

(def ^:private cli-options
  [["-o" "--outdir DIR" "Output directory for the process (default: a temp dir)"]
   [nil  "--backend NAME" "Preprocessing backend: cwljava (default), clojure, or schema-salad-tool"
    :parse-fn keyword
    :validate [#{:cwljava :clojure :schema-salad-tool} "must be cwljava, clojure, or schema-salad-tool"]]
   [nil  "--log-file PATH" "Write a detailed EDN run log to PATH"]
   [nil  "--progress" "Force the live progress display on stderr"]
   [nil  "--no-progress" "Disable the live progress display (use plain lines)"]
   ["-v" "--verbose" "Verbose run feedback on stderr (debug level)"]
   ["-q" "--quiet" "Quiet: only warnings and errors on stderr"]
   ["-h" "--help" "Show this help and exit"]
   ["-V" "--version" "Show version and exit"]])

(defn- progress-enabled?
  "Whether to show the live progress display. Conservative default: off unless
   `--progress` is passed. (The JVM has no cheap stderr-isatty check and
   `System/console` is nil whenever stdout is redirected — the common
   `… > out.json` case — so we don't auto-enable; the user opts in.) `-q`/
   `--no-progress` keep it off."
  [options]
  (boolean
   (and (:progress options)
        (not (:no-progress options))
        (not (:quiet options)))))

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

(defn- parent-dir
  "Absolute parent directory of a path string, or nil."
  [path]
  (some-> path io/file .getAbsoluteFile .getParent))

(defn run-document
  "Run the CWL `cwl-file` against the (optional) `job-file`, returning the bound
   outputs.

   Following cwltool, relative `File` paths in the job file resolve against the
   job file's directory (`:job-basedir`) and the document's own references
   resolve against the document's directory (`:basedir`); both are derived from
   the file arguments here so a run works from any working directory. Explicit
   `opts` override the derived bases. `opts` are passed through to
   `fleur.process/run-file`."
  ([cwl-file job-file] (run-document cwl-file job-file {}))
  ([cwl-file job-file opts]
   (let [doc-basedir (parent-dir cwl-file)
         job-basedir (or (parent-dir job-file) doc-basedir)
         opts (merge {:basedir doc-basedir :job-basedir job-basedir} opts)]
     (:boundOutputs (process/run-file cwl-file (load-job job-file) opts)))))

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
                   (:backend options) (assoc :backend (:backend options)))
            console-level (cond (:quiet options)   :warn
                                (:verbose options) :debug
                                :else              :info)]
        (log/init! {:log-file (:log-file options)
                    :console-level console-level
                    :progress? (progress-enabled? options)})
        (try
          (let [result (run-document cwl-file job-file opts)]
            (log/shutdown!)                     ; flush stderr/file before the result
            (println (json/write-str result))
            (flush)
            (System/exit 0))
          (catch Throwable e
            (binding [*out* *err*] (println "cwl-runner error:" (.getMessage e)))
            (log/shutdown!)
            (System/exit 1)))))))
