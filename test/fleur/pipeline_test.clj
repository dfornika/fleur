(ns fleur.pipeline-test
  "Structural smoke tests for multi-file workflows and the variant-calling
   pipeline. These load/preprocess the CWL and (for the hermetic case) run it;
   they do NOT execute bwa/samtools/bcftools, which need those tools + data."
  (:require [clojure.test :refer [deftest testing is]]
            [fleur.preprocess :as pre]
            [fleur.process :as process]))

(deftest multi-file-workflow-test
  (testing "a workflow whose steps reference processes by file (run: x.cwl) runs
            end-to-end after cwljava resolves the references"
    (is (= 11 (get-in (process/run-file "resources/multifile/math.cwl" {:x 5})
                      [:boundOutputs :result])))))

(def ^:private variant-calling-tools
  ["bwa-index" "bwa-mem" "samtools-sort" "samtools-index" "samtools-faidx"
   "bcftools-mpileup" "bcftools-call"])

(deftest variant-calling-tools-load-test
  (testing "each variant-calling CommandLineTool is well-formed and loads"
    (doseq [t variant-calling-tools]
      (let [doc (pre/preprocess-file (str "workflows/variant-calling/" t ".cwl"))]
        (is (= "CommandLineTool" (:class doc)) t)
        (is (some? (:baseCommand doc)) t)))))

(deftest variant-calling-workflow-loads-test
  (testing "the variant-calling workflow loads with the expected structure"
    (let [wf (pre/preprocess-file "workflows/variant-calling/variant-calling.cwl")]
      (is (= "Workflow" (:class wf)))
      (is (= 7 (count (:steps wf))))
      (is (= "call/variants" (get-in wf [:outputs :variants :outputSource]))))))
