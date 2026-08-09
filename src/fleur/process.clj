(ns fleur.process
  "Run a CWL process, dispatching on its `class`. This is the uniform entry
   point over the process types Fleur supports (CommandLineTool and
   ExpressionTool today); workflow steps will run through here too."
  (:require [fleur.command-line-tool :as clt]
            [fleur.expression-tool :as et]))

(defn run
  "Run `tool` against `provided-inputs`, dispatching on its `:class`.
   `opts` are passed through to the process-specific runner."
  ([tool provided-inputs] (run tool provided-inputs {}))
  ([tool provided-inputs opts]
   (case (some-> (:class tool) name)
     "CommandLineTool" (clt/run tool provided-inputs opts)
     "ExpressionTool"  (et/run tool provided-inputs opts)
     (throw (ex-info (str "Unsupported process class: " (pr-str (:class tool)))
                     {:class (:class tool)})))))
