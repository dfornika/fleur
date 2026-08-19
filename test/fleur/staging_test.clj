(ns fleur.staging-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [fleur.staging :as stg])
  (:import [java.nio.file Files LinkOption]))

(defn- with-temp-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "fleur-stg-" (System/nanoTime)))]
    (.mkdirs dir)
    (try (f dir)
         (finally (doseq [x (reverse (file-seq dir))] (.delete x))))))

;;; ---------------------------------------------------------------------------
;;; Path resolution and metadata
;;; ---------------------------------------------------------------------------

(deftest resolve-file-test
  (testing "relative paths become absolute with populated metadata"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "reads.fastq.gz") "data")
        (let [resolved (stg/resolve-file (.getPath dir)
                                         {:class "File" :path "reads.fastq.gz"})]
          (is (= (.getPath (io/file dir "reads.fastq.gz")) (:path resolved)))
          (is (= "reads.fastq.gz" (:basename resolved)))
          (is (= (.getPath dir) (:dirname resolved)))
          (is (= "reads.fastq" (:nameroot resolved)))
          (is (= ".gz" (:nameext resolved)))
          (is (= 4 (:size resolved)))))))

  (testing "a dotfile has no extension"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir ".bashrc") "")
        (let [r (stg/resolve-file (.getPath dir) {:class "File" :path ".bashrc"})]
          (is (= ".bashrc" (:nameroot r)))
          (is (= "" (:nameext r)))))))

  (testing "an already-absolute path is left absolute"
    (is (= "/etc/hosts"
           (:path (stg/resolve-file "/whatever" {:class "File" :path "/etc/hosts"})))))

  (testing "non-File values pass through"
    (is (= 42 (stg/resolve-file "/base" 42)))))

(deftest resolve-inputs-test
  (testing "File inputs (including arrays) are resolved against base-dir"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "a.txt") "")
        (let [tool {:inputs {:f {:type "File"
                                 :value {:class "File" :path "a.txt"}}
                             :n {:type "int" :value 5}}}
              resolved (stg/resolve-inputs tool (.getPath dir))]
          (is (= (.getPath (io/file dir "a.txt"))
                 (get-in resolved [:inputs :f :value :path])))
          (is (= 5 (get-in resolved [:inputs :n :value]))))))))

(deftest load-contents-inputs-test
  (testing "loadContents attaches file contents only to inputs that request it"
    (with-temp-dir
      (fn [dir]
        (spit (io/file dir "read.txt") "hello\ncontents\n")
        (spit (io/file dir "skip.txt") "not loaded\n")
        (let [tool {:inputs {:loaded  {:type "File" :loadContents true
                                       :value {:class "File" :path "read.txt"}}
                             :plain   {:type "File"
                                       :value {:class "File" :path "skip.txt"}}}}
              out (-> tool (stg/resolve-inputs (.getPath dir)) stg/load-contents-inputs)]
          (is (= "hello\ncontents\n" (get-in out [:inputs :loaded :value :contents])))
          (is (nil? (get-in out [:inputs :plain :value :contents]))))))))

(deftest load-contents-size-limit-test
  (testing "a file 64 KiB or smaller loads"
    (with-temp-dir
      (fn [dir]
        (let [f (io/file dir "ok.txt")]
          (spit f (apply str (repeat stg/load-contents-limit \a)))
          (is (= stg/load-contents-limit (count (stg/load-contents (.getPath f)))))))))
  (testing "a file larger than 64 KiB raises a fatal error (no truncation)"
    (with-temp-dir
      (fn [dir]
        (let [f (io/file dir "big.txt")]
          (spit f (apply str (repeat (inc stg/load-contents-limit) \a)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"64 KiB"
                                (stg/load-contents (.getPath f)))))))))

;;; ---------------------------------------------------------------------------
;;; Staging into a working directory
;;; ---------------------------------------------------------------------------

(deftest stage-file-copy-test
  (testing "a file is copied into the target dir and its path updated"
    (with-temp-dir
      (fn [dir]
        (let [src-dir (io/file dir "src") tgt (io/file dir "work")]
          (.mkdirs src-dir) (.mkdirs tgt)
          (spit (io/file src-dir "in.txt") "payload")
          (let [staged (stg/stage-file! (.getPath tgt)
                                        {:class "File"
                                         :path (.getPath (io/file src-dir "in.txt"))
                                         :basename "in.txt"})]
            (is (= (.getPath (io/file tgt "in.txt")) (:path staged)))
            (is (.exists (io/file tgt "in.txt")))
            (is (= "payload" (slurp (io/file tgt "in.txt"))))))))))

(deftest stage-file-symlink-test
  (testing "symlink method creates a symbolic link"
    (with-temp-dir
      (fn [dir]
        (let [src (io/file dir "orig.txt") tgt (io/file dir "work")]
          (.mkdirs tgt)
          (spit src "x")
          (stg/stage-file! (.getPath tgt)
                           {:class "File" :path (.getPath src) :basename "orig.txt"}
                           {:method :symlink})
          (is (Files/isSymbolicLink (.toPath (io/file tgt "orig.txt")))))))))

(deftest stage-inputs-test
  (testing "all File inputs are staged into the target dir"
    (with-temp-dir
      (fn [dir]
        (let [tgt (io/file dir "work")]
          (.mkdirs tgt)
          (spit (io/file dir "data.bam") "bam")
          (let [tool {:inputs {:bam {:type "File"
                                     :value {:class "File"
                                             :path (.getPath (io/file dir "data.bam"))
                                             :basename "data.bam"}}}}
                staged (stg/stage-inputs! tool (.getPath tgt))]
            (is (= (.getPath (io/file tgt "data.bam"))
                   (get-in staged [:inputs :bam :value :path])))
            (is (.exists (io/file tgt "data.bam")))))))))

;;; ---------------------------------------------------------------------------
;;; InitialWorkDirRequirement
;;; ---------------------------------------------------------------------------

(deftest initial-work-dir-literal-content-test
  (testing "a Dirent with literal string content writes a file"
    (with-temp-dir
      (fn [dir]
        (let [tool {:requirements [{:class "InitialWorkDirRequirement"
                                    :listing [{:entryname "config.txt"
                                               :entry "hello config"}]}]}]
          (stg/initial-work-dir! tool (.getPath dir))
          (is (= "hello config" (slurp (io/file dir "config.txt")))))))))

(deftest initial-work-dir-expression-content-test
  (testing "a Dirent :entry expression is evaluated for its content"
    (with-temp-dir
      (fn [dir]
        (let [tool {:requirements [{:class "InitialWorkDirRequirement"
                                    :listing [{:entryname "greeting.txt"
                                               :entry "$(inputs.msg)"}]}]}
              ctx {:inputs {:msg "hi there"}}]
          (stg/initial-work-dir! tool (.getPath dir) ctx false)
          (is (= "hi there" (slurp (io/file dir "greeting.txt")))))))))

(deftest initial-work-dir-file-entry-test
  (testing "a File listing entry is copied into the working directory"
    (with-temp-dir
      (fn [dir]
        (let [src (io/file dir "template.txt") work (io/file dir "work")]
          (.mkdirs work)
          (spit src "template body")
          (let [tool {:requirements [{:class "InitialWorkDirRequirement"
                                      :listing [{:class "File"
                                                 :path (.getPath src)
                                                 :basename "template.txt"}]}]}]
            (stg/initial-work-dir! tool (.getPath work))
            (is (= "template body" (slurp (io/file work "template.txt"))))))))))
