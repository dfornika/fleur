(ns fleur.expression-tool-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [fleur.expression-tool :as et]))

(deftest bind-outputs-test
  (testing "result object fields are mapped onto declared outputs by id"
    (is (= {:a 1 :b nil}
           (:boundOutputs (et/bind-outputs {:outputs {:a {} :b {}}} {:a 1})))))
  (testing "a non-map result yields nil for every output"
    (is (= {:a nil}
           (:boundOutputs (et/bind-outputs {:outputs {:a {}}} 42))))))

(deftest run-javascript-test
  (testing "evaluates a ${...} expression and maps the object to outputs"
    (let [tool {:class "ExpressionTool"
                :requirements [{:class "InlineJavascriptRequirement"}]
                :inputs {:message {:type "string"}}
                :outputs {:uppercased {:type "string"}}
                :expression "${ return {uppercased: inputs.message.toUpperCase()}; }"}
          result (et/run tool {:message "hello"})]
      (is (= "HELLO" (get-in result [:boundOutputs :uppercased])))))

  (testing "multiple outputs, including an integer"
    (let [tool {:class "ExpressionTool"
                :requirements [{:class "InlineJavascriptRequirement"}]
                :inputs {:x {:type "int"}}
                :outputs {:n {:type "int"} :s {:type "string"}}
                :expression "${ return {n: inputs.x * 2, s: 'v' + inputs.x}; }"}
          out (:boundOutputs (et/run tool {:x 21}))]
      (is (= 42 (:n out)))
      (is (= "v21" (:s out))))))

(deftest run-parameter-reference-test
  (testing "a parameter-reference expression needs no InlineJavascriptRequirement"
    (let [tool {:class "ExpressionTool"
                :inputs {:obj {:type "Any"}}
                :outputs {:out {:type "int"}}
                :expression "$(inputs.obj)"}
          out (:boundOutputs (et/run tool {:obj {:out 7}}))]
      (is (= 7 (:out out))))))

(deftest run-requires-inline-javascript-test
  (testing "a ${...} expression without InlineJavascriptRequirement is rejected"
    (let [tool {:class "ExpressionTool"
                :inputs {}
                :outputs {:x {:type "int"}}
                :expression "${ return {x: 1}; }"}]
      (is (thrown? clojure.lang.ExceptionInfo (et/run tool {}))))))

(deftest run-from-cwl-resource-test
  (testing "the parse_int ExpressionTool sample runs (map-shaped requirements)"
    (let [tool (yaml/parse-string (slurp (io/resource "parse_int.cwl")))
          out (:boundOutputs (et/run tool {:number_string "42"}))]
      (is (= 42 (:parsed out))))))
