(ns fleur.schema-salad
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.shell :as shell]))

(defn preprocess-via-shell
  "Pre-process a cwl file by shelling out to schema-salad-tool"
  [f]
  (let [preprocess-result (shell/sh "schema-salad-tool" "--print-pre" (str f))
        exit-status (:exit preprocess-result)]
    (cond (= exit-status 0)
          (json/read-str (:out preprocess-result) :key-fn keyword)
          :else preprocess-result)))

(defn preprocess
  "Pre-process a cwl file"
  [f]
  (let [file-str (slurp f)]
    (yaml/parse-string file-str)))
  

(comment
  (let [hello-world-tool-file (io/resource "hello_world.cwl")]
    (->> hello-world-tool-file
        slurp
        yaml/parse-string
        (into {})))

  (let [hello-world-tool-file (io/resource "hello_world.cwl")]
    (preprocess hello-world-tool-file))
  )
