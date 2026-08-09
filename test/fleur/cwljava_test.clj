(ns fleur.cwljava-test
  "Exploration of the cwljava (schema-salad-generated Java SDK) preprocessing
   backend. cwljava is an OPTIONAL dependency pulled via the :cwljava alias
   (JitPack), so these tests are guarded: they run under
   `clojure -X:test:cwljava` and skip under plain `clojure -X:test`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fleur.preprocess :as pre]
            [fleur.process :as process]))

(defn- cwljava-available? []
  (try (Class/forName "org.commonwl.cwlsdk.cwl1_2.utils.RootLoader")
       true
       (catch Throwable _ false)))

(deftest cwljava-preprocess-test
  (if-not (cwljava-available?)
    (is true "cwljava not on the classpath (run with -X:test:cwljava); skipped")
    (testing "cwljava loads a CommandLineTool into Fleur's map representation"
      (let [doc (pre/preprocess-file "resources/javac.cwl" {:backend :cwljava})]
        (is (= "CommandLineTool" (:class doc)))
        (is (= "javac" (:baseCommand doc)))
        (is (some #{"$(runtime.outdir)"} (:arguments doc)))
        (testing "inputs are normalized to map form with resolved types"
          (is (= "File" (get-in doc [:inputs :src :type])))
          (is (= 1 (get-in doc [:inputs :src :inputBinding :position]))))
        (testing "outputs likewise"
          (is (= "File" (get-in doc [:inputs :src :type])))
          (is (= "*.class" (get-in doc [:outputs :classfile :outputBinding :glob]))))
        (testing "requirements/hints survive (DockerRequirement in hints)"
          (is (some #(= "DockerRequirement" (:class %)) (:hints doc))))))))

(deftest cwljava-run-end-to-end-test
  (if-not (cwljava-available?)
    (is true "cwljava not on the classpath (run with -X:test:cwljava); skipped")
    (testing "a cwljava-loaded tool runs through the normal pipeline"
      (let [tool (pre/preprocess-file "resources/hello_world.cwl" {:backend :cwljava})
            result (process/run tool {:message "cwljava end-to-end"})]
        (is (zero? (:exit (:executionResult result))))
        (is (str/includes? (:out (:executionResult result)) "cwljava end-to-end"))))))

(deftest cwljava-missing-classpath-error-test
  (testing "the :cwljava backend errors clearly when cwljava is absent"
    (when-not (cwljava-available?)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"classpath"
                            (pre/preprocess-file "resources/hello_world.cwl" {:backend :cwljava}))))))
