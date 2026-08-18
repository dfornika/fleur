(ns fleur.workflow-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [ubergraph.alg :as alg]
            [fleur.workflow :as wf]))

;;; ---------------------------------------------------------------------------
;;; Dependency graph
;;; ---------------------------------------------------------------------------

(deftest dependency-graph-test
  (testing "edges run from producer step to consumer, giving a valid topo order"
    (let [steps {:a {:in {} :out [:o]}
                 :b {:in {:i {:source "a/o"}} :out [:o]}
                 :c {:in {:i {:source "b/o"}} :out [:o]}}
          order (alg/topsort (wf/dependency-graph steps))]
      (is (= [:a :b :c] order))))

  (testing "a diamond keeps the fan-in step last"
    (let [steps {:a {:in {} :out [:o]}
                 :b {:in {:i {:source "a/o"}} :out [:o]}
                 :c {:in {:i {:source "a/o"}} :out [:o]}
                 :d {:in {:x {:source "b/o"} :y {:source "c/o"}} :out [:o]}}
          order (vec (alg/topsort (wf/dependency-graph steps)))]
      (is (= :a (first order)))
      (is (= :d (last order))))))

;;; ---------------------------------------------------------------------------
;;; Linear execution (pure ExpressionTool steps)
;;; ---------------------------------------------------------------------------

(defn- et-step [expression]
  {:class "ExpressionTool"
   :inputs {:n {:type "int"}}
   :outputs {:out {:type "int"}}
   :expression expression})

(def inline-math-workflow
  {:class "Workflow"
   :requirements [{:class "InlineJavascriptRequirement"}]
   :inputs {:x {:type "int"}}
   :outputs {:result {:type "int" :outputSource "increment/out"}}
   :steps {:increment {:in {:n {:source "double/out"}} :out [:out]
                       :run (et-step "${ return {out: inputs.n + 1}; }")}
           :double {:in {:n {:source "x"}} :out [:out]
                    :run (et-step "${ return {out: inputs.n * 2}; }")}}})

(deftest run-linear-inline-test
  (testing "steps run in dependency order, wiring outputs to downstream inputs"
    (is (= 11 (get-in (wf/run inline-math-workflow {:x 5}) [:boundOutputs :result]))))

  (testing "InlineJavascriptRequirement declared on the workflow is inherited by steps"
    ;; (the ExpressionTool steps use ${...}; they'd throw without the inherited requirement)
    (is (= 21 (get-in (wf/run inline-math-workflow {:x 10}) [:boundOutputs :result])))))

(deftest run-input-default-test
  (testing "a workflow input default is used when the value isn't provided"
    (let [w (assoc-in inline-math-workflow [:inputs :x] {:type "int" :default 3})]
      (is (= 7 (get-in (wf/run w {}) [:boundOutputs :result]))))))

(deftest run-from-cwl-resource-test
  (testing "the linear_math.cwl sample runs end-to-end (steps out of declared order)"
    (let [w (yaml/parse-string (slurp (io/resource "linear_math.cwl")))]
      (is (= 11 (get-in (wf/run w {:x 5}) [:boundOutputs :result]))))))

;;; ---------------------------------------------------------------------------
;;; Cycle detection
;;; ---------------------------------------------------------------------------

(deftest cycle-detection-test
  (testing "a cyclic workflow is rejected before any step runs"
    (let [w {:class "Workflow"
             :inputs {} :outputs {}
             :steps {:a {:in {:i {:source "b/o"}} :out [:o] :run (et-step "$(inputs.n)")}
                     :b {:in {:i {:source "a/o"}} :out [:o] :run (et-step "$(inputs.n)")}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle" (wf/run w {}))))))

;;; ---------------------------------------------------------------------------
;;; A CommandLineTool step within a workflow
;;; ---------------------------------------------------------------------------

(deftest run-command-line-tool-step-test
  (testing "a CommandLineTool step runs and its File output wires to the workflow output"
    (let [w {:class "Workflow"
             :inputs {:message {:type "string"}}
             :outputs {:out {:type "File" :outputSource "echo/echoed"}}
             :steps {:echo {:in {:message {:source "message"}}
                            :out [:echoed]
                            :run {:class "CommandLineTool"
                                  :baseCommand "echo"
                                  :stdout "echoed.txt"
                                  :inputs {:message {:type "string"
                                                     :inputBinding {:position 1}}}
                                  :outputs {:echoed {:type "File"
                                                     :outputBinding {:glob "echoed.txt"}}}}}}}
          out (get-in (wf/run w {:message "hi from workflow"}) [:boundOutputs :out])]
      (is (= "echoed.txt" (:basename out)))
      (is (= "hi from workflow" (str/trim (slurp (:path out))))))))

;;; ---------------------------------------------------------------------------
;;; Inline sub-workflow (with cwljava-style over-qualified sources)
;;; ---------------------------------------------------------------------------

(deftest run-inline-subworkflow-test
  (testing "a step whose run is an inline Workflow executes, and enclosing-scope
            qualified sources (inner/x, inner/double/out) are canonicalized"
    (let [inner {:class "Workflow"
                 :inputs {:x {:type "int"}}
                 ;; cwljava scopes these with the enclosing step id `inner`:
                 :outputs {:result {:type "int" :outputSource "inner/increment/out"}}
                 :steps {:double {:in {:n {:source "inner/x"}} :out [:out]
                                  :run (et-step "${ return {out: inputs.n * 2}; }")}
                         :increment {:in {:n {:source "inner/double/out"}} :out [:out]
                                     :run (et-step "${ return {out: inputs.n + 1}; }")}}}
          outer {:class "Workflow"
                 :requirements [{:class "InlineJavascriptRequirement"}]
                 :inputs {:x {:type "int"}}
                 :outputs {:result {:type "int" :outputSource "inner/result"}}
                 :steps {:inner {:in {:x {:source "x"}} :out [:result] :run inner}}}]
      (is (= 11 (get-in (wf/run outer {:x 5}) [:boundOutputs :result]))))))
