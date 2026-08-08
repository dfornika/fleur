(ns fleur.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [fleur.runtime :as rt]))

(deftest make-runtime-defaults-test
  (testing "outdir and tmpdir are created directories"
    (let [{:keys [outdir tmpdir]} (rt/make-runtime {})]
      (is (.isDirectory (io/file outdir)))
      (is (.isDirectory (io/file tmpdir)))
      (is (not= outdir tmpdir))))

  (testing "cores defaults to a positive number and ram to 256 MiB"
    (let [{:keys [cores ram]} (rt/make-runtime {})]
      (is (pos? cores))
      (is (= 256 ram)))))

(deftest make-runtime-resource-requirement-test
  (testing "cores/ram come from a ResourceRequirement when numeric"
    (let [tool {:requirements [{:class "ResourceRequirement"
                                :coresMin 3 :ramMin 512}]}
          {:keys [cores ram]} (rt/make-runtime tool)]
      (is (= 3 cores))
      (is (= 512 ram))))

  (testing "hints are also consulted"
    (let [tool {:hints [{:class "ResourceRequirement" :coresMax 8}]}]
      (is (= 8 (:cores (rt/make-runtime tool)))))))

(deftest make-runtime-explicit-opts-test
  (testing "explicit options win over requirements and defaults"
    (let [tool {:requirements [{:class "ResourceRequirement" :coresMin 3}]}
          rtm (rt/make-runtime tool {:outdir "/tmp/fixed-out" :cores 1 :ram 64})]
      (is (= "/tmp/fixed-out" (:outdir rtm)))
      (is (= 1 (:cores rtm)))
      (is (= 64 (:ram rtm))))))

(deftest find-requirement-test
  (testing "supports the map-keyed-by-class shape"
    (is (= {:dockerPull "img"}
           (rt/find-requirement {:hints {:DockerRequirement {:dockerPull "img"}}}
                                "DockerRequirement"))))
  (testing "returns nil when absent"
    (is (nil? (rt/find-requirement {} "ResourceRequirement")))))
