(ns fleur.preprocess-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fleur.preprocess :as pre]
            [fleur.command-line-tool :as clt]))

;;; ---------------------------------------------------------------------------
;;; Type DSL
;;; ---------------------------------------------------------------------------

(deftest expand-type-string-test
  (is (= "int" (pre/expand-type-string "int")))
  (is (= ["null" "int"] (pre/expand-type-string "int?")))
  (is (= {:type "array" :items "File"} (pre/expand-type-string "File[]")))
  (testing "stacked suffixes, outermost first"
    (is (= ["null" {:type "array" :items "int"}] (pre/expand-type-string "int[]?")))
    (is (= {:type "array" :items ["null" "int"]} (pre/expand-type-string "int?[]")))))

(deftest expand-type-test
  (testing "unions expand element-wise"
    (is (= ["null" {:type "array" :items "File"}]
           (pre/expand-type ["null" "File[]"]))))
  (testing "array :items DSL is expanded"
    (is (= {:type "array" :items {:type "array" :items "int"}}
           (pre/expand-type {:type "array" :items "int[]"}))))
  (testing "record field types are expanded"
    (is (= {:type "record" :fields {:x {:type ["null" "int"]}}}
           (pre/expand-type {:type "record" :fields {:x {:type "int?"}}})))))

;;; ---------------------------------------------------------------------------
;;; inputs/outputs normalization
;;; ---------------------------------------------------------------------------

(deftest preprocess-inputs-test
  (testing "the param: type shorthand becomes {:type ...}"
    (is (= {:inputs {:message {:type "string"}}}
           (pre/preprocess {:inputs {:message "string"}} {:backend :clojure}))))

  (testing "optional and array DSL expand"
    (is (= {:inputs {:a {:type ["null" "int"]}
                     :b {:type {:type "array" :items "File"}}}}
           (pre/preprocess {:inputs {:a "int?" :b "File[]"}} {:backend :clojure}))))

  (testing "list-form inputs normalize to map form"
    (is (= {:inputs {:x {:type "int"}}}
           (pre/preprocess {:inputs [{:id "x" :type "int"}]} {:backend :clojure}))))

  (testing "already-full-form specs are preserved (position kept, type expanded)"
    (is (= {:inputs {:f {:type {:type "array" :items "File"}
                         :inputBinding {:position 1}}}}
           (pre/preprocess {:inputs {:f {:type "File[]" :inputBinding {:position 1}}}}
                           {:backend :clojure})))))

(deftest preprocess-recurses-into-steps-test
  (testing "a workflow step's inline run process is preprocessed"
    (let [wf {:class "Workflow"
              :steps {:s {:in {} :out [:o]
                          :run {:class "ExpressionTool"
                                :inputs {:n "int?"}
                                :outputs {:out "int"}
                                :expression "$(inputs.n)"}}}}
          out (pre/preprocess wf {:backend :clojure})]
      (is (= {:type ["null" "int"]} (get-in out [:steps :s :run :inputs :n])))
      (is (= {:type "int"} (get-in out [:steps :s :run :outputs :out]))))))

;;; ---------------------------------------------------------------------------
;;; Deferred passes fail loudly; unknown backend errors
;;; ---------------------------------------------------------------------------

(deftest deferred-passes-throw-test
  (testing "$graph is rejected by the native backend"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\$graph"
                          (pre/preprocess {:$graph [{:class "CommandLineTool"}]}
                                          {:backend :clojure}))))
  (testing "$import is rejected by the native backend"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\$import"
                          (pre/preprocess {:inputs {:x {:$import "types.yml"}}}
                                          {:backend :clojure})))))

(deftest unknown-backend-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"backend"
                        (pre/preprocess {:inputs {}} {:backend :nope}))))

;;; ---------------------------------------------------------------------------
;;; File loading + end-to-end payoff
;;; ---------------------------------------------------------------------------

(deftest preprocess-file-test
  (testing "preprocess-file parses YAML and applies the native subset"
    (let [f (io/file (System/getProperty "java.io.tmpdir")
                     (str "fleur-pre-" (System/nanoTime) ".cwl"))]
      (try
        (spit f "class: CommandLineTool\nbaseCommand: echo\ninputs:\n  message: string?\noutputs: []\n")
        (is (= {:type ["null" "string"]}
               (get-in (pre/preprocess-file f {:backend :clojure}) [:inputs :message])))
        (finally (.delete f))))))

(deftest preprocess-enables-shorthand-run-test
  (testing "a tool written with shorthand inputs runs after preprocessing"
    (let [raw {:class "CommandLineTool"
               :baseCommand "echo"
               :stdout "out.txt"
               :inputs {:message {:type "string" :inputBinding {:position 1}}}
               :outputs {:out {:type "File" :outputBinding {:glob "out.txt"}}}}
          ;; write the type as shorthand to prove preprocessing normalizes it
          shorthand (assoc-in raw [:inputs :message :type] "string")
          result (-> shorthand (pre/preprocess {:backend :clojure}) (clt/run {:message "hi there"}))
          out (get-in result [:boundOutputs :out])]
      (is (zero? (:exit (:executionResult result))))
      (is (= "hi there" (str/trim (slurp (:path out))))))))
