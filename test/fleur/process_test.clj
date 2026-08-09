(ns fleur.process-test
  (:require [clojure.test :refer [deftest testing is]]
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

(deftest dispatch-unknown-class-test
  (testing "an unsupported class throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (process/run {:class "Workflow"} {})))))
