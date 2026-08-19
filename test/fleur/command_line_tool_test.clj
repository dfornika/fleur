(ns fleur.command-line-tool-test
  "Tests for behaviour we consider correct. These should stay green."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-yaml.core :as yaml]
            [fleur.command-line-tool :as t]))

;;; ---------------------------------------------------------------------------
;;; Input value association
;;; ---------------------------------------------------------------------------

(deftest assoc-inputs-with-values-test
  (testing "provided job values are assoc'd under each input's :value"
    (is (= {:inputs {:message {:type "string" :value "hi"}}}
           (t/assoc-inputs-with-values
            {:inputs {:message {:type "string"}}}
            {:message "hi"}))))

  (testing "multiple inputs are all associated"
    (is (= {:inputs {:a {:value 1} :b {:value 2}}}
           (t/assoc-inputs-with-values
            {:inputs {:a {} :b {}}}
            {:a 1 :b 2})))))

;;; ---------------------------------------------------------------------------
;;; Default values
;;; ---------------------------------------------------------------------------

(deftest assoc-default-if-exists-test
  (testing "a :default becomes the :value"
    (is (= {:type "string" :default "d" :value "d"}
           (t/assoc-default-if-exists {:type "string" :default "d"}))))

  (testing "no :default leaves the input untouched"
    (is (= {:type "string"}
           (t/assoc-default-if-exists {:type "string"})))))

(deftest assoc-inputs-with-default-values-test
  (testing "defaults are applied per-input, others left alone"
    (is (= {:inputs {:m {:default "x" :value "x"} :n {}}}
           (t/assoc-inputs-with-default-values
            {:inputs {:m {:default "x"} :n {}}})))))

;;; ---------------------------------------------------------------------------
;;; Sorting by inputBinding position
;;; ---------------------------------------------------------------------------

(deftest input-sorter-test
  (testing "reads :position from :inputBinding"
    (is (= 3 (t/input-sorter [:a {:inputBinding {:position 3}}]))))

  (testing "defaults to 0 when no position is present"
    (is (= 0 (t/input-sorter [:a {}])))))

(deftest sort-inputs-test
  (testing "inputs are ordered by their binding position"
    (is (= [:b :a :c]
           (map first
                (t/sort-inputs
                 {:a {:inputBinding {:position 2}}
                  :b {:inputBinding {:position 1}}
                  :c {:inputBinding {:position 3}}}))))))

;;; ---------------------------------------------------------------------------
;;; Command line construction (CWL CommandLineTool algorithm)
;;; ---------------------------------------------------------------------------

(deftest build-command-line-scalar-inputs-test
  (testing "a string input is emitted as-is after baseCommand"
    (is (= '("echo" "Hello World")
           (:commandLine
            (t/build-command-line
             {:baseCommand "echo"
              :inputs {:message {:type "string" :value "Hello World"
                                 :inputBinding {:position 1}}}})))))

  (testing "a File input is resolved to its path"
    (is (= '("javac" "resources/Hello.java")
           (:commandLine
            (t/build-command-line
             {:baseCommand "javac"
              :inputs {:src {:type "File"
                             :value {:class "File" :path "resources/Hello.java"}
                             :inputBinding {:position 1}}}}))))))

(deftest build-command-line-base-command-test
  (testing "a vector baseCommand is spliced, not nested"
    (is (= '("tar" "--extract")
           (:commandLine
            (t/build-command-line {:baseCommand ["tar" "--extract"] :inputs {}})))))

  (testing "a missing baseCommand contributes nothing"
    (is (= '("value")
           (:commandLine
            (t/build-command-line
             {:inputs {:a {:type "string" :value "value"
                           :inputBinding {:position 1}}}}))))))

(deftest build-command-line-position-test
  (testing "inputs are ordered by inputBinding position, not map order"
    (is (= '("cmd" "B" "A")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs (array-map
                       :a {:type "string" :value "A" :inputBinding {:position 2}}
                       :b {:type "string" :value "B" :inputBinding {:position 1}})}))))))

(deftest build-command-line-prefix-test
  (testing "a prefix is emitted as a separate token by default"
    (is (= '("tar" "--file" "hello.tar")
           (:commandLine
            (t/build-command-line
             {:baseCommand "tar"
              :inputs {:tarfile {:type "string" :value "hello.tar"
                                 :inputBinding {:prefix "--file"}}}})))))

  (testing "separate=false concatenates prefix and value into one token"
    (is (= '("cmd" "-Ivalue")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:x {:type "string" :value "value"
                           :inputBinding {:prefix "-I" :separate false}}}}))))))

(deftest build-command-line-arguments-test
  (testing "arguments precede positioned inputs and keep their order"
    (is (= '("javac" "-d" "outdir" "Hello.java")
           (:commandLine
            (t/build-command-line
             {:baseCommand "javac"
              :arguments ["-d" "outdir"]
              :inputs {:src {:type "string" :value "Hello.java"
                             :inputBinding {:position 1}}}}))))))

(deftest build-command-line-boolean-test
  (testing "a true boolean emits only its prefix"
    (is (= '("cmd" "--verbose")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:v {:type "boolean" :value true
                           :inputBinding {:prefix "--verbose"}}}})))))

  (testing "a false boolean emits nothing"
    (is (= '("cmd")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:v {:type "boolean" :value false
                           :inputBinding {:prefix "--verbose"}}}}))))))

(deftest build-command-line-array-test
  (testing "an array with itemSeparator joins into one token"
    (is (= '("cmd" "-I" "a,b,c")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:xs {:type "array" :value ["a" "b" "c"]
                            :inputBinding {:prefix "-I" :itemSeparator ","}}}})))))

  (testing "an array without itemSeparator emits the prefix then each element"
    (is (= '("cmd" "-I" "a" "b")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:xs {:type "array" :value ["a" "b"]
                            :inputBinding {:prefix "-I"}}}}))))))

(deftest build-command-line-null-and-no-binding-test
  (testing "a null value contributes nothing"
    (is (= '("cmd")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:opt {:type "string" :value nil
                             :inputBinding {:prefix "--opt"}}}})))))

  (testing "an input without an inputBinding is not placed on the command line"
    (is (= '("cmd")
           (:commandLine
            (t/build-command-line
             {:baseCommand "cmd"
              :inputs {:hidden {:type "string" :value "x"}}}))))))

;;; ---------------------------------------------------------------------------
;;; Command line construction with expression evaluation (2-arity)
;;; ---------------------------------------------------------------------------

(deftest build-command-line-literal-without-context-test
  (testing "without a context, expressions are emitted literally"
    (is (= '("javac" "-d" "$(runtime.outdir)" "Hello.java")
           (:commandLine
            (t/build-command-line
             {:baseCommand "javac"
              :arguments ["-d" "$(runtime.outdir)"]
              :inputs {:src {:type "string" :value "Hello.java"
                             :inputBinding {:position 1}}}}))))))

(deftest build-command-line-parameter-reference-test
  (testing "arguments' parameter references resolve against the context"
    (let [tool {:baseCommand "javac"
                :arguments ["-d" "$(runtime.outdir)"]
                :inputs {:src {:type "File"
                               :value {:class "File" :path "resources/Hello.java"}
                               :inputBinding {:position 1}}}}
          ctx (t/evaluation-context tool {:outdir "/tmp/out"})]
      (is (= '("javac" "-d" "/tmp/out" "resources/Hello.java")
             (:commandLine (t/build-command-line tool ctx)))))))

(deftest build-command-line-value-from-test
  (testing "an input inputBinding :valueFrom is evaluated with self = value"
    (let [tool {:baseCommand "echo"
                :inputs {:x {:type "string" :value "abc"
                             :inputBinding {:position 1 :valueFrom "$(self)-suffix"}}}}
          ctx (t/evaluation-context tool {})]
      (is (= '("echo" "abc-suffix")
             (:commandLine (t/build-command-line tool ctx)))))))

(deftest build-command-line-inline-javascript-test
  (testing "InlineJavascriptRequirement enables JS in expressions"
    (let [tool {:baseCommand "echo"
                :requirements [{:class "InlineJavascriptRequirement"}]
                :arguments [{:valueFrom "$(inputs.n * 2)"}]
                :inputs {:n {:type "int" :value 21}}}
          ctx (t/evaluation-context tool {})]
      (is (= '("echo" "42")
             (:commandLine (t/build-command-line tool ctx))))))

  (testing "without InlineJavascriptRequirement a JS expression is rejected"
    (let [tool {:baseCommand "echo"
                :arguments [{:valueFrom "$(inputs.n * 2)"}]
                :inputs {:n {:type "int" :value 21}}}
          ctx (t/evaluation-context tool {})]
      (is (thrown? clojure.lang.ExceptionInfo (t/build-command-line tool ctx))))))

;;; ---------------------------------------------------------------------------
;;; Glob matching for outputs
;;; ---------------------------------------------------------------------------

(deftest glob-pattern-to-regex-test
  (testing "* matches any run of characters, . is literal"
    (let [re (t/glob-pattern-to-regex "*.class")]
      (is (re-matches re "Hello.class"))
      (is (not (re-matches re "Hello.java")))))

  (testing "a plain filename only matches itself"
    (let [re (t/glob-pattern-to-regex "hello.txt")]
      (is (re-matches re "hello.txt"))
      (is (not (re-matches re "helloXtxt"))))))

(defn- with-temp-dir
  "Create a temp dir, call (f dir), then recursively delete it."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "fleur-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(deftest glob-files-test
  (testing "only files matching the pattern are returned"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "a.class") "")
        (spit (io/file dir "b.class") "")
        (spit (io/file dir "c.java") "")
        (let [matches (t/glob-files (.getPath dir) "*.class")]
          (is (= 2 (count matches)))
          (is (= #{"a.class" "b.class"}
                 (set (map #(.getName (io/file %)) matches)))))))))

;;; ---------------------------------------------------------------------------
;;; Output binding branches that don't touch the filesystem
;;; ---------------------------------------------------------------------------

(deftest bind-outputs-test
  (testing "a tool with no outputs is returned unchanged"
    (let [tool {:baseCommand "echo"}]
      (is (= tool (t/bind-outputs tool)))))

  (testing "an output with no glob reports an error"
    (is (= {:result {:error "No glob pattern specified"}}
           (:boundOutputs
            (t/bind-outputs {:outputs {:result {:type "File"
                                                :outputBinding {}}}}))))))

;;; ---------------------------------------------------------------------------
;;; Output binding with a context: glob expressions, secondaryFiles, format
;;; ---------------------------------------------------------------------------

(deftest bind-outputs-collects-from-outdir-test
  (testing "files are collected from runtime.outdir, array output type"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "a.class") "")
        (spit (io/file dir "b.class") "")
        (let [tool {:outputs {:cls {:type "File[]" :outputBinding {:glob "*.class"}}}}
              ctx {:runtime {:outdir (.getPath dir)}}
              cls (:cls (:boundOutputs (t/bind-outputs tool ctx)))]
          (is (= 2 (count cls)))
          (is (= #{"a.class" "b.class"} (set (map :basename cls)))))))))

(deftest bind-outputs-glob-expression-test
  (testing "an outputBinding :glob expression is evaluated against the context"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "report.txt") "")
        (let [tool {:outputs {:r {:type "File" :outputBinding {:glob "$(inputs.name)"}}}}
              ctx {:runtime {:outdir (.getPath dir)} :inputs {:name "report.txt"}}
              r (:r (:boundOutputs (t/bind-outputs tool ctx)))]
          (is (= "report.txt" (:basename r))))))))

(deftest bind-outputs-secondary-files-test
  (testing "secondaryFiles are resolved by suffix and caret rules, if they exist"
    (with-temp-dir
      (fn [dir]
        (doseq [f ["reads.bam" "reads.bai" "reads.bam.md5"]]
          (spit (io/file dir f) ""))
        (let [tool {:outputs {:bam {:type "File"
                                    :outputBinding {:glob "reads.bam"}
                                    :secondaryFiles ["^.bai" ".md5"]}}}
              ctx {:runtime {:outdir (.getPath dir)}}
              bam (:bam (:boundOutputs (t/bind-outputs tool ctx)))]
          (is (= #{"reads.bai" "reads.bam.md5"}
                 (set (map :basename (:secondaryFiles bam))))))))))

(deftest bind-outputs-format-test
  (testing "an output :format is attached (and evaluated) on the bound File"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "d.txt") "")
        (let [tool {:outputs {:o {:type "File" :format "http://example.org/fmt"
                                  :outputBinding {:glob "d.txt"}}}}
              ctx {:runtime {:outdir (.getPath dir)}}
              o (:o (:boundOutputs (t/bind-outputs tool ctx)))]
          (is (= "http://example.org/fmt" (:format o))))))))

;;; ---------------------------------------------------------------------------
;;; Execution: stdin / stdout / stderr redirection
;;; ---------------------------------------------------------------------------

(deftest execute-stdout-redirection-test
  (testing "captured stdout is written to the named file under outdir"
    (with-temp-dir
      (fn [dir]
        (let [tool {:commandLine ["echo" "hello world"] :stdout "out.txt"}
              ctx {:runtime {:outdir (.getPath dir)}}
              result (t/execute tool ctx)]
          (is (zero? (:exit (:executionResult result))))
          (is (= "hello world" (str/trim (slurp (io/file dir "out.txt"))))))))))

(deftest execute-stdin-redirection-test
  (testing "stdin is redirected from the named file"
    (with-temp-dir
      (fn [dir]
        (let [in (io/file dir "in.txt")]
          (spit in "piped content")
          (let [tool {:commandLine ["cat"] :stdin (.getPath in) :stdout "o.txt"}
                ctx {:runtime {:outdir (.getPath dir)}}]
            (t/execute tool ctx)
            (is (= "piped content" (slurp (io/file dir "o.txt"))))))))))

(deftest execute-env-var-requirement-test
  (testing "EnvVarRequirement variables (incl. expressions) are set in the env"
    (with-temp-dir
      (fn [dir]
        (let [tool {:commandLine ["sh" "-c" "printf %s \"$GREETING\""]
                    :requirements [{:class "EnvVarRequirement"
                                    :envDef {:GREETING "$(inputs.msg)"}}]
                    :inputs {:msg {:type "string" :value "hi there"}}
                    :stdout "o.txt"}
              ctx (t/evaluation-context tool {:outdir (.getPath dir)})]
          (t/execute tool ctx)
          (is (= "hi there" (slurp (io/file dir "o.txt")))))))))

;;; ---------------------------------------------------------------------------
;;; End-to-end run
;;; ---------------------------------------------------------------------------

(deftest run-end-to-end-test
  (testing "run builds runtime + context, executes, and binds outputs"
    (let [tool {:baseCommand "echo"
                :arguments ["hi there"]
                :stdout "message.txt"
                :inputs {}
                :outputs {:out {:type "File" :outputBinding {:glob "message.txt"}}}}
          result (t/run tool {})
          out (:out (:boundOutputs result))]
      (is (zero? (:exit (:executionResult result))))
      (is (= "message.txt" (:basename out)))
      (is (= "hi there" (str/trim (slurp (:path out))))))))

(deftest run-tar-extract-integration-test
  (testing "the tar_extract sample runs end-to-end: a relative input path is
            resolved to absolute, tar runs in outdir, and the output is bound"
    (let [tool (yaml/parse-string (slurp (io/resource "tar_extract.cwl")))
          job  (json/read-str (slurp (io/resource "tar_extract-job.json")) :key-fn keyword)
          result (t/run tool job)
          out (:example_out (:boundOutputs result))]
      (is (str/ends-with? (nth (:commandLine result) 3) "/resources/hello.tar")
          "the relative tarfile path was resolved to an absolute path")
      (is (zero? (:exit (:executionResult result))))
      (is (= "hello.txt" (:basename out)))
      (is (.exists (io/file (:path out)))))))
