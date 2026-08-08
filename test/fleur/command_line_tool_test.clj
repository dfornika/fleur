(ns fleur.command-line-tool-test
  "Tests for behaviour we consider correct. These should stay green."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
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
