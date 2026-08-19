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

;;; ---------------------------------------------------------------------------
;;; Scatter / gather
;;; ---------------------------------------------------------------------------

(defn- add-step []
  {:class "ExpressionTool"
   :inputs {:x {:type "int"} :y {:type "int"}}
   :outputs {:out {:type "int"}}
   :expression "${ return {out: inputs.x + inputs.y}; }"})

(defn- scatter-wf
  "A one-step workflow that scatters `add-step` over inputs a and/or b."
  [step-in scatter method out-src]
  {:class "Workflow"
   :requirements [{:class "InlineJavascriptRequirement"}]
   :inputs {:a {:type "int[]"} :b {:type "int[]"}}
   :outputs {:result {:type "int[]" :outputSource out-src}}
   :steps {:s (cond-> {:in step-in :out [:out] :scatter scatter :run (add-step)}
                method (assoc :scatterMethod method))}})

(deftest scatter-single-test
  (testing "scattering one input runs the step per element and gathers an array"
    (let [w {:class "Workflow"
             :requirements [{:class "InlineJavascriptRequirement"}]
             :inputs {:numbers {:type "int[]"}}
             :outputs {:doubled {:type "int[]" :outputSource "double/out"}}
             :steps {:double {:in {:n {:source "numbers"}} :out [:out] :scatter [:n]
                              :run (et-step "${ return {out: inputs.n * 2}; }")}}}]
      (is (= [2 4 6] (get-in (wf/run w {:numbers [1 2 3]}) [:boundOutputs :doubled])))))
  (testing "an empty scatter input yields an empty output array (no jobs run)"
    (let [w {:class "Workflow"
             :requirements [{:class "InlineJavascriptRequirement"}]
             :inputs {:numbers {:type "int[]"}}
             :outputs {:doubled {:type "int[]" :outputSource "double/out"}}
             :steps {:double {:in {:n {:source "numbers"}} :out [:out] :scatter [:n]
                              :run (et-step "${ return {out: inputs.n * 2}; }")}}}]
      (is (= [] (get-in (wf/run w {:numbers []}) [:boundOutputs :doubled]))))))

(deftest scatter-dotproduct-test
  (testing "dotproduct zips the arrays element-wise"
    (let [w (scatter-wf {:x {:source "a"} :y {:source "b"}} [:x :y] :dotproduct "s/out")]
      (is (= [11 22 33]
             (get-in (wf/run w {:a [1 2 3] :b [10 20 30]}) [:boundOutputs :result])))))
  (testing "dotproduct requires equal-length arrays"
    (let [w (scatter-wf {:x {:source "a"} :y {:source "b"}} [:x :y] :dotproduct "s/out")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"equal-length"
                            (wf/run w {:a [1 2] :b [10]}))))))

(deftest scatter-crossproduct-test
  (testing "flat_crossproduct produces one flat array over every combination"
    (let [w (scatter-wf {:x {:source "a"} :y {:source "b"}} [:x :y] :flat_crossproduct "s/out")]
      (is (= [11 101 12 102]
             (get-in (wf/run w {:a [1 2] :b [10 100]}) [:boundOutputs :result])))))
  (testing "nested_crossproduct nests one array level per scattered input"
    (let [w (scatter-wf {:x {:source "a"} :y {:source "b"}} [:x :y] :nested_crossproduct "s/out")]
      (is (= [[11 101] [12 102]]
             (get-in (wf/run w {:a [1 2] :b [10 100]}) [:boundOutputs :result]))))))

(deftest scatter-non-array-input-test
  (testing "a missing or non-array scattered input is a fatal error, not empty output"
    (let [w {:class "Workflow"
             :requirements [{:class "InlineJavascriptRequirement"}]
             :inputs {:numbers {:type "int[]"}}
             :outputs {:doubled {:type "int[]" :outputSource "double/out"}}
             :steps {:double {:in {:n {:source "numbers"}} :out [:out] :scatter [:n]
                              :run (et-step "${ return {out: inputs.n * 2}; }")}}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an array"
                            (wf/run w {})))                      ; missing -> nil
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an array"
                            (wf/run w {:numbers 5}))))))          ; scalar

;;; ---------------------------------------------------------------------------
;;; Conditional execution (when)
;;; ---------------------------------------------------------------------------

(defn- when-wf [when-expr]
  {:class "Workflow"
   :requirements [{:class "InlineJavascriptRequirement"}]
   :inputs {:x {:type "int"} :run_it {:type "boolean"}}
   :outputs {:result {:type ["null" "int"] :outputSource "maybe/out"}}
   :steps {:maybe {:in {:n {:source "x"} :run_it {:source "run_it"}}
                   :out [:out] :when when-expr
                   :run (et-step "${ return {out: inputs.n * 2}; }")}}})

(deftest conditional-when-test
  (testing "a step whose when is false is skipped; its outputs are null"
    (is (nil? (get-in (wf/run (when-wf "$(inputs.run_it)") {:x 5 :run_it false})
                      [:boundOutputs :result]))))
  (testing "a step whose when is true runs normally"
    (is (= 10 (get-in (wf/run (when-wf "$(inputs.run_it)") {:x 5 :run_it true})
                      [:boundOutputs :result]))))
  (testing "a when expression that is not boolean is a fatal error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"true or false"
                          (wf/run (when-wf "$(inputs.x)") {:x 5 :run_it true})))))

(deftest scatter-with-when-test
  (testing "when is evaluated per scatter job; skipped jobs are null in the array"
    (let [w {:class "Workflow"
             :requirements [{:class "InlineJavascriptRequirement"}]
             :inputs {:numbers {:type "int[]"}}
             :outputs {:doubled {:type "int[]" :outputSource "double/out"}}
             :steps {:double {:in {:n {:source "numbers"}} :out [:out] :scatter [:n]
                              :when "$(inputs.n > 2)"
                              :run (et-step "${ return {out: inputs.n * 2}; }")}}}]
      (is (= [nil nil 6 8]
             (get-in (wf/run w {:numbers [1 2 3 4]}) [:boundOutputs :doubled]))))))

;;; ---------------------------------------------------------------------------
;;; Multi-source inputs (linkMerge)
;;; ---------------------------------------------------------------------------

(deftest linkmerge-test
  (let [sum-step {:class "ExpressionTool"
                  :inputs {:nums {:type "int[]"}}
                  :outputs {:out {:type "int"}}
                  :expression "${ return {out: inputs.nums.reduce(function(a,b){return a+b;}, 0)}; }"}
        wf (fn [link-merge run]
             {:class "Workflow"
              :requirements [{:class "InlineJavascriptRequirement"}]
              :inputs {:a {:type "int"} :b {:type "int"}}
              :outputs {:total {:type "int" :outputSource "collect/out"}}
              :steps {:left  {:in {:n {:source "a"}} :out [:out]
                              :run {:class "ExpressionTool" :inputs {:n {:type "int"}}
                                    :outputs {:out {:type "int[]"}}
                                    :expression "${ return {out: [inputs.n, inputs.n + 1]}; }"}}
                      :right {:in {:n {:source "b"}} :out [:out]
                              :run {:class "ExpressionTool" :inputs {:n {:type "int"}}
                                    :outputs {:out {:type "int[]"}}
                                    :expression "${ return {out: [inputs.n, inputs.n + 1]}; }"}}
                      :collect {:in {:nums {:source ["left/out" "right/out"] :linkMerge link-merge}}
                                :out [:out] :run run}}})]
    (testing "merge_flattened concatenates the array sources one level"
      ;; left=[1,2], right=[10,11] -> [1,2,10,11] -> 24
      (is (= 24 (get-in (wf/run (wf "merge_flattened" sum-step) {:a 1 :b 10})
                        [:boundOutputs :total]))))
    (testing "merge_nested keeps one entry per source (a nested array)"
      (let [nested-sum {:class "ExpressionTool"
                        :inputs {:nums {:type "Any"}}
                        :outputs {:out {:type "int"}}
                        :expression "${ var t=0; inputs.nums.forEach(function(a){a.forEach(function(x){t+=x;});}); return {out:t}; }"}]
        ;; [[1,2],[10,11]] -> 24
        (is (= 24 (get-in (wf/run (wf "merge_nested" nested-sum) {:a 1 :b 10})
                          [:boundOutputs :total])))))))
