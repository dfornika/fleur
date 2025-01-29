(ns fleur.schema-salad
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]))

(defn preprocess
  "Pre-process a cwl file by shelling out to schema-salad-tool"
  [f]
  (let [preprocess-result (shell/sh "schema-salad-tool" "--print-pre" (str f))
        exit-status (:exit preprocess-result)]
    (cond (= exit-status 0)
          (json/read-str (:out preprocess-result) :key-fn keyword))))
  
  
