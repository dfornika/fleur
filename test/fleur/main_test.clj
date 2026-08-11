(ns fleur.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [fleur.main :as main]))

(defn- with-temp-job [content f]
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "fleur-job-" (System/nanoTime) ".json"))]
    (spit file content)
    (try (f (.getPath file)) (finally (.delete file)))))

(deftest load-job-test
  (testing "a nil job file yields an empty map (defaults are used)"
    (is (= {} (main/load-job nil))))
  (testing "a JSON job file loads as a keyword-keyed map"
    (with-temp-job "{\"x\": 5}" (fn [p] (is (= {:x 5} (main/load-job p)))))))

(deftest run-document-workflow-test
  (testing "run a Workflow file with a job file, returning bound outputs"
    (with-temp-job "{\"x\": 5}"
      (fn [p] (is (= 11 (:result (main/run-document "resources/linear_math.cwl" p))))))))

(deftest run-document-file-input-test
  (testing "run a CommandLineTool with a File input"
    (let [out (main/run-document "resources/tar_extract.cwl" "resources/tar_extract-job.json")]
      (is (= "hello.txt" (get-in out [:example_out :basename]))))))
