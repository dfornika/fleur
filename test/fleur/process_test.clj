(ns fleur.process-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [fleur.process :as process]))

(deftest dispatch-expression-tool-test
  (testing "an ExpressionTool is dispatched and evaluated"
    (let [tool {:class "ExpressionTool"
                :requirements [{:class "InlineJavascriptRequirement"}]
                :inputs {:x {:type "int"}}
                :outputs {:doubled {:type "int"}}
                :expression "${ return {doubled: inputs.x * 2}; }"}]
      (is (= 10 (get-in (process/run tool {:x 5}) [:boundOutputs :doubled]))))))

(deftest dispatch-command-line-tool-test
  (testing "a CommandLineTool is dispatched and built/run"
    (let [tool {:class "CommandLineTool"
                :baseCommand "echo"
                :arguments ["hi"]
                :stdout "out.txt"
                :inputs {}
                :outputs {:o {:type "File" :outputBinding {:glob "out.txt"}}}}
          result (process/run tool {})]
      (is (zero? (:exit (:executionResult result))))
      (is (= "out.txt" (get-in result [:boundOutputs :o :basename]))))))

(deftest dispatch-workflow-test
  (testing "a Workflow is dispatched (via requiring-resolve) to the workflow runner"
    (let [wf {:class "Workflow"
              :requirements [{:class "InlineJavascriptRequirement"}]
              :inputs {:x {:type "int"}}
              :outputs {:result {:type "int" :outputSource "double/out"}}
              :steps {:double {:in {:n {:source "x"}} :out [:out]
                               :run {:class "ExpressionTool"
                                     :inputs {:n {:type "int"}}
                                     :outputs {:out {:type "int"}}
                                     :expression "${ return {out: inputs.n * 2}; }"}}}}]
      (is (= 8 (get-in (process/run wf {:x 4}) [:boundOutputs :result]))))))

(deftest dispatch-unknown-class-test
  (testing "an unsupported class throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (process/run {:class "Operation"} {})))))

;;; ---------------------------------------------------------------------------
;;; run-file: preprocess (cwljava by default) a CWL file, then run it
;;; ---------------------------------------------------------------------------

(deftest run-file-command-line-tool-test
  (testing "a CommandLineTool file is preprocessed and run"
    (let [r (process/run-file "resources/hello_world.cwl" {:message "run-file works"})]
      (is (zero? (:exit (:executionResult r))))
      (is (str/includes? (:out (:executionResult r)) "run-file works")))))

(deftest run-file-with-file-input-test
  (testing "the tar_extract sample runs from disk, resolving its File input"
    (let [job (json/read-str (slurp (io/resource "tar_extract-job.json")) :key-fn keyword)
          r (process/run-file "resources/tar_extract.cwl" job)]
      (is (zero? (:exit (:executionResult r))))
      (is (= "hello.txt" (get-in r [:boundOutputs :example_out :basename]))))))

(deftest run-file-workflow-test
  (testing "a Workflow file is preprocessed (refs shortened) and run end-to-end"
    (let [r (process/run-file "resources/linear_math.cwl" {:x 5})]
      (is (= 11 (get-in r [:boundOutputs :result]))))))
