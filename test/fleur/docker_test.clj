(ns fleur.docker-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [fleur.docker :as docker]
            [fleur.command-line-tool :as t]))

;;; ---------------------------------------------------------------------------
;;; Requirement detection and image resolution (pure)
;;; ---------------------------------------------------------------------------

(deftest docker-requirement-test
  (testing "found in requirements or hints"
    (is (= {:class "DockerRequirement" :dockerPull "img"}
           (docker/docker-requirement
            {:requirements [{:class "DockerRequirement" :dockerPull "img"}]})))
    (is (some? (docker/docker-requirement
                {:hints [{:class "DockerRequirement" :dockerPull "img"}]}))))
  (testing "dockerized? reflects presence"
    (is (docker/dockerized? {:hints [{:class "DockerRequirement" :dockerPull "x"}]}))
    (is (not (docker/dockerized? {:baseCommand "echo"})))))

(deftest docker-image-test
  (testing "dockerImageId takes precedence over dockerPull"
    (is (= "local:tag" (docker/docker-image {:dockerImageId "local:tag" :dockerPull "img"})))
    (is (= "img" (docker/docker-image {:dockerPull "img"})))))

(deftest container-outdir-test
  (is (= "/var/spool/cwl" (docker/container-outdir {})))
  (is (= "/out" (docker/container-outdir {:dockerOutputDirectory "/out"}))))

;;; ---------------------------------------------------------------------------
;;; Mount planning and path remapping (pure)
;;; ---------------------------------------------------------------------------

(def sample-tool
  {:inputs (array-map
            :b {:value {:class "File" :path "/data/b.txt" :basename "b.txt"}}
            :a {:value {:class "File" :path "/data/a.txt" :basename "a.txt"
                        :secondaryFiles [{:class "File" :path "/data/a.idx" :basename "a.idx"}]}})})

(deftest plan-mounts-test
  (let [{:keys [mounts path-map]}
        (docker/plan-mounts sample-tool "/host/out" "/host/tmp" "/var/spool/cwl" "/tmp")]
    (testing "inputs are mounted read-only, sorted by input name, secondaries co-located"
      (is (= "/var/lib/cwl/inputs/0/a.txt" (path-map "/data/a.txt")))
      (is (= "/var/lib/cwl/inputs/0/a.idx" (path-map "/data/a.idx")))
      (is (= "/var/lib/cwl/inputs/1/b.txt" (path-map "/data/b.txt"))))

    (testing "outdir and tmpdir are mounted read-write, last"
      (is (= {:host "/host/out" :container "/var/spool/cwl" :mode "rw"}
             (nth mounts (- (count mounts) 2))))
      (is (= {:host "/host/tmp" :container "/tmp" :mode "rw"}
             (last mounts))))

    (testing "every input mount is read-only"
      (is (every? #(= "ro" (:mode %)) (take 3 mounts))))))

(deftest remap-tool-paths-test
  (let [{:keys [path-map]} (docker/plan-mounts sample-tool "/o" "/t" "/var/spool/cwl" "/tmp")
        remapped (docker/remap-tool-paths sample-tool path-map)]
    (testing "input File paths become container paths, basename preserved"
      (is (= "/var/lib/cwl/inputs/1/b.txt"
             (get-in remapped [:inputs :b :value :path])))
      (is (= "b.txt" (get-in remapped [:inputs :b :value :basename])))
      (is (= "/var/lib/cwl/inputs/1" (get-in remapped [:inputs :b :value :dirname]))))
    (testing "secondaryFiles are remapped too"
      (is (= "/var/lib/cwl/inputs/0/a.idx"
             (get-in remapped [:inputs :a :value :secondaryFiles 0 :path]))))))

(deftest docker-run-argv-test
  (testing "assembles docker run with mounts, workdir, image and command"
    (is (= ["docker" "run" "--rm" "-i"
            "-v" "/h/in:/c/in:ro"
            "-v" "/h/out:/var/spool/cwl"
            "-w" "/var/spool/cwl" "busybox:latest"
            "cat" "/c/in"]
           (docker/docker-run-argv
            "busybox:latest"
            [{:host "/h/in" :container "/c/in" :mode "ro"}
             {:host "/h/out" :container "/var/spool/cwl" :mode "rw"}]
            "/var/spool/cwl"
            ["cat" "/c/in"])))))

;;; ---------------------------------------------------------------------------
;;; End-to-end container execution (requires a Docker daemon + busybox image)
;;; ---------------------------------------------------------------------------

(defn- docker-available? []
  (try (zero? (:exit (shell/sh "docker" "info"))) (catch Exception _ false)))

(deftest docker-run-integration-test
  (if-not (and (docker-available?) (docker/image-present? "busybox:latest"))
    (is true "docker daemon or busybox image unavailable; integration test skipped")
    (let [in (io/file (System/getProperty "java.io.tmpdir")
                      (str "fleur-docker-in-" (System/nanoTime) ".txt"))]
      (try
        (spit in "inside the container\n")
        (let [tool {:class "CommandLineTool"
                    :requirements [{:class "DockerRequirement" :dockerPull "busybox:latest"}]
                    :baseCommand ["cat"]
                    :stdout "out.txt"
                    :inputs {:f {:type "File"
                                 :value {:class "File" :path (.getPath in)}
                                 :inputBinding {:position 1}}}
                    :outputs {:o {:type "File" :outputBinding {:glob "out.txt"}}}}
              result (t/run tool {})
              cmd (:commandLine result)
              out (:o (:boundOutputs result))]
          (testing "the command is a docker run wrapping the tool"
            (is (= ["docker" "run" "--rm" "-i"] (take 4 cmd)))
            (is (some #{"busybox:latest"} cmd)))
          (testing "the input is referenced by its container path"
            (is (some #(and (string? %) (.startsWith % "/var/lib/cwl/inputs/")) cmd)))
          (testing "the container ran and produced the bound output"
            (is (zero? (:exit (:executionResult result))))
            (is (= "inside the container\n" (slurp (:path out))))))
        (finally (.delete in))))))
