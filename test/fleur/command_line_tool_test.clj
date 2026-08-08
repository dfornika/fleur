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
