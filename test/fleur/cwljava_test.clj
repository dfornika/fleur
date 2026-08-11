(ns fleur.cwljava-test
  "Tests for the cwljava preprocessing backend, which is Fleur's default backend
   (`fleur.preprocess/default-backend`). cwljava is a regular dependency pulled
   from GitHub via JitPack, so these run as part of the normal suite."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [fleur.preprocess :as pre]
            [fleur.process :as process]))

(deftest default-backend-test
  (testing "cwljava is the default preprocessing backend"
    (is (= :cwljava pre/default-backend))))

(deftest cwljava-preprocess-file-test
  (testing "cwljava (the default) loads a CommandLineTool into Fleur's map form"
    (let [doc (pre/preprocess-file "resources/javac.cwl")]
      (is (= "CommandLineTool" (:class doc)))
      (is (= "javac" (:baseCommand doc)))
      (is (some #{"$(runtime.outdir)"} (:arguments doc)))
      (testing "inputs/outputs are normalized to map form with resolved types"
        (is (= "File" (get-in doc [:inputs :src :type])))
        (is (= 1 (get-in doc [:inputs :src :inputBinding :position])))
        (is (= "*.class" (get-in doc [:outputs :classfile :outputBinding :glob]))))
      (testing "requirements/hints survive (DockerRequirement in hints)"
        (is (some #(= "DockerRequirement" (:class %)) (:hints doc)))))))

(deftest cwljava-preprocess-map-test
  (testing "cwljava also preprocesses an already-parsed document map"
    (let [parsed (yaml/parse-string (slurp (io/resource "hello_world.cwl")))
          doc (pre/preprocess parsed {:backend :cwljava})]
      (is (= "CommandLineTool" (:class doc)))
      (is (= "echo" (:baseCommand doc))))))

(deftest cwljava-run-end-to-end-test
  (testing "a cwljava-loaded tool runs through the normal pipeline"
    (let [tool (pre/preprocess-file "resources/hello_world.cwl")
          result (process/run tool {:message "cwljava end-to-end"})]
      (is (zero? (:exit (:executionResult result))))
      (is (str/includes? (:out (:executionResult result)) "cwljava end-to-end")))))
