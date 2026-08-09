(ns fleur.expression-tool
  "Execution of CWL `ExpressionTool` processes.

   An ExpressionTool has no command; instead its `expression` is evaluated (as a
   CWL expression / JavaScript) against the bound inputs and must yield a JSON
   object whose fields correspond to the tool's declared outputs. See
   <https://www.commonwl.org/v1.2/Workflow.html#ExpressionTool>."
  (:require [fleur.command-line-tool :as clt]
            [fleur.expression :as expr]
            [fleur.runtime :as rt]
            [fleur.staging :as stg]))

(defn bind-outputs
  "Map an ExpressionTool's evaluated `result` (expected to be an object) onto the
   tool's declared outputs by matching output id, storing them under
   `:boundOutputs`."
  [tool result]
  (let [bound (into {}
                    (for [[output-key _] (:outputs tool)]
                      [output-key (when (map? result) (get result output-key))]))]
    (assoc tool :boundOutputs bound)))

(defn run
  "Run an ExpressionTool: apply defaults and `provided-inputs`, resolve input
   File paths, build the runtime/evaluation context, evaluate `:expression`, and
   map the result onto the declared outputs. JavaScript is used when the tool
   declares InlineJavascriptRequirement (as ExpressionTools normally do).

   `opts` accepts `:basedir` (for resolving relative input paths) and the
   `fleur.runtime/make-runtime` options."
  ([tool provided-inputs] (run tool provided-inputs {}))
  ([tool provided-inputs {:keys [basedir] :as opts}]
   (let [basedir (or basedir (System/getProperty "user.dir"))
         tool (-> tool
                  clt/assoc-inputs-with-default-values
                  (clt/assoc-inputs-with-values provided-inputs)
                  (stg/resolve-inputs basedir))
         runtime (rt/make-runtime tool opts)
         context (clt/evaluation-context tool runtime)
         js? (clt/inline-javascript? tool)
         result (expr/evaluate (:expression tool) context {:js? js?})]
     (-> tool
         (assoc :expressionResult result)
         (bind-outputs result)))))
