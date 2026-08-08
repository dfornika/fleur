(ns fleur.command-line-tool-known-issues-test
  "Characterization tests that pin down CURRENT (buggy) behaviour of the
   command-line builder. Each test asserts what the code does today and
   documents, in a `DESIRED:` comment, what it should do instead. When we
   fix the corresponding bug, flip the assertion to the desired behaviour.

   Keeping these green (against today's behaviour) means the suite as a
   whole passes, while making the outstanding work explicit and testable."
  (:require [clojure.test :refer [deftest testing is]]
            [fleur.command-line-tool :as t]))

(deftest file-input-is-not-resolved-to-its-path
  ;; `format-input-value` matches on (:keyword (:type input)), but after
  ;; parsing the type is the plain string "File", so the :File branch never
  ;; fires and the whole File map is left in place.
  (testing "File inputs are left as the full File map instead of a path"
    (let [built (t/build-command-line
                 {:baseCommand "javac"
                  :inputs {:src {:type "File"
                                 :value {:class "File" :path "resources/Hello.java"}
                                 :inputBinding {:position 1}}}})]
      (is (= '("javac" {:class "File" :path "resources/Hello.java"})
             (:commandLine built)))
      ;; DESIRED: '("javac" "resources/Hello.java")
      )))

(deftest vector-base-command-is-nested
  ;; `(concat [base-command] ...)` wraps a vector baseCommand in another
  ;; vector instead of splicing its elements.
  (testing "a vector baseCommand ends up nested in the command line"
    (let [built (t/build-command-line
                 {:baseCommand ["tar" "--extract"]
                  :inputs {}})]
      (is (= '(["tar" "--extract"])
             (:commandLine built)))
      ;; DESIRED: '("tar" "--extract")
      )))

(deftest input-binding-position-is-ignored-when-building
  ;; `build-command-line` computes `sorted-tool-inputs` but never uses it;
  ;; it emits inputs in map order rather than by :position.
  (testing "command line follows map order, not inputBinding position"
    (let [built (t/build-command-line
                 {:baseCommand "cmd"
                  :inputs (array-map
                           :a {:type "string" :value "A" :inputBinding {:position 2}}
                           :b {:type "string" :value "B" :inputBinding {:position 1}})})]
      (is (= '("cmd" "A" "B")
             (:commandLine built)))
      ;; DESIRED: '("cmd" "B" "A")  (ordered by position)
      )))

(deftest input-binding-prefix-is-dropped
  ;; `build-command-line` never looks at :prefix, so prefixes like `--file`
  ;; are silently discarded.
  (testing "an inputBinding :prefix is not emitted"
    (let [built (t/build-command-line
                 {:baseCommand "tar"
                  :inputs {:tarfile {:type "string"
                                     :value "hello.tar"
                                     :inputBinding {:prefix "--file"}}}})]
      (is (= '("tar" "hello.tar")
             (:commandLine built)))
      ;; DESIRED: '("tar" "--file" "hello.tar")
      )))

(deftest arguments-are-not-included
  ;; CWL `arguments` are not incorporated into the command line at all.
  (testing "top-level :arguments are ignored by build-command-line"
    (let [built (t/build-command-line
                 {:baseCommand "javac"
                  :arguments ["-d" "outdir"]
                  :inputs {:src {:type "string" :value "Hello.java"
                                 :inputBinding {:position 1}}}})]
      (is (= '("javac" "Hello.java")
             (:commandLine built)))
      ;; DESIRED: '("javac" "-d" "outdir" "Hello.java")
      )))
