(ns fleur.process
  "Run a CWL process, dispatching on its `class`. This is the uniform entry
   point over the process types Fleur supports (CommandLineTool, ExpressionTool,
   and Workflow); workflow steps also run through here."
  (:require [fleur.command-line-tool :as clt]
            [fleur.expression-tool :as et]
            [fleur.preprocess :as pre]))

(defn run
  "Run an already-parsed CWL process `tool` against `provided-inputs`,
   dispatching on its `:class`. `opts` are passed through to the process-specific
   runner. The document is assumed to be preprocessed/normalized — use `run-file`
   (or preprocess first) for raw CWL that needs type-DSL/`$import`/`$graph`
   handling."
  ([tool provided-inputs] (run tool provided-inputs {}))
  ([tool provided-inputs opts]
   (case (some-> (:class tool) name)
     "CommandLineTool" (clt/run tool provided-inputs opts)
     "ExpressionTool"  (et/run tool provided-inputs opts)
     ;; Resolved at call time to avoid a compile-time cycle
     ;; (fleur.workflow requires fleur.process to run its steps).
     "Workflow"        ((requiring-resolve 'fleur.workflow/run) tool provided-inputs opts)
     (throw (ex-info (str "Unsupported process class: " (pr-str (:class tool)))
                     {:class (:class tool)})))))

(defn run-file
  "Preprocess a CWL file (via `fleur.preprocess/preprocess-file`, cwljava by
   default) and run the resulting process against `provided-inputs`. `opts` flow
   to both the preprocessor (`:backend`, `:basedir`) and the runner. This is the
   end-to-end entry point for running a CWL document from disk."
  ([f provided-inputs] (run-file f provided-inputs {}))
  ([f provided-inputs opts]
   (run (pre/preprocess-file f (select-keys opts [:backend :basedir]))
        provided-inputs
        opts)))
