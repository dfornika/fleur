(ns fleur.basedir-test
  "Path-resolution semantics: job-file `File` paths resolve against the job
   file's directory, document references against the document's directory
   (cwltool behavior), while the programmatic API keeps a cwd default."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [fleur.main :as main]
            [fleur.process :as process]))

(defn- with-temp-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "fleur-basedir-" (System/nanoTime)))]
    (.mkdirs dir)
    (try (f dir)
         (finally (run! #(.delete %) (reverse (file-seq dir)))))))

(deftest job-file-relative-different-dirs-test
  (testing "a job file's relative File path resolves against the job file's dir,
            even when the document lives elsewhere and cwd is the repo root"
    (with-temp-dir
      (fn [dir]
        (io/copy (io/file "resources/hello.tar") (io/file dir "hello.tar"))
        (let [job (io/file dir "job.json")]
          (spit job "{\"tarfile\":{\"class\":\"File\",\"path\":\"hello.tar\"}}")
          (let [out (main/run-document "resources/tar_extract.cwl" (.getPath job))]
            (is (= "hello.txt" (get-in out [:example_out :basename])))))))))

(deftest absolute-job-path-test
  (testing "an absolute File path in the job file resolves regardless of base"
    (with-temp-dir
      (fn [dir]
        (let [abs (.getAbsolutePath (io/file "resources/hello.tar"))
              job (io/file dir "job.json")]
          (spit job (str "{\"tarfile\":{\"class\":\"File\",\"path\":\"" abs "\"}}"))
          (let [out (main/run-document "resources/tar_extract.cwl" (.getPath job))]
            (is (= "hello.txt" (get-in out [:example_out :basename])))))))))

(deftest run-file-cwd-relative-backcompat-test
  (testing "the programmatic API defaults job-basedir to cwd: a repo-root
            relative path still resolves (no migration for existing callers)"
    (let [r (process/run-file "resources/tar_extract.cwl"
                              {:tarfile {:class "File" :path "resources/hello.tar"}})]
      (is (= "hello.txt" (get-in r [:boundOutputs :example_out :basename]))))))
