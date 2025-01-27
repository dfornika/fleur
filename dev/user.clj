(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.java.shell :as shell]
            [clojure.tools.trace :as trace]
            [clojure.data.json :as json]
            [clj-yaml.core :as yaml]
            [fleur.command-line-tool :as tool]))

(def hello-world-tool-file
  (io/resource "hello_world.cwl"))

(def hello-world-job-file
  (io/resource "hello_world-job.json"))

(def hello-world-job
  (-> hello-world-job-file
      slurp
      (json/read-str :key-fn keyword)))

(def hello-world-tool
  (-> hello-world-tool-file
      slurp
      yaml/parse-string))

(def javac-tool-file
  (io/resource "javac.cwl"))

(def javac-job-file
  (io/resource "javac-job.json"))

(def javac-tool
  (-> javac-tool-file
      slurp
      yaml/parse-string))

(def javac-job
  (-> javac-job-file
      slurp
      (json/read-str :key-fn keyword)))


(comment
  (-> hello-world-tool
      tool/assoc-inputs-with-default-values
      (tool/assoc-inputs-with-values hello-world-job)
      tool/build-command-line
      tool/execute
      )
  )

(def common-fields
  [:id
   :name
   :doc
   :label])

(def cwl-types
  [:null
   :boolean
   :int
   :long
   :float
   :double
   :string
   :File
   :Directory])

(def software-package-requirement-template
  {:package ""
   :version [""]
   :specs [""]})
   

(def docker-requirement-template
  {:class :DockerRequirement
   :dockerPull ""
   :dockerLoad ""
   :dockerFile ""
   :dockerImport ""
   :dockerImageId ""
   :dockerOutputDirectory ""})

(def command-input-record-field-template
  {:name ""
   :type nil
   :doc ""
   :label ""
   :secondaryFiles []
   :streamable false
   :format nil
   :loadContents nil
   :loadListing nil
   :inputBinding nil})

(def command-line-binding-template
  {:loadContents false
   :position 0
   :prefix ""
   :separate false
   :itemSeparator " "
   :valueFrom ""
   :shellQuote true})

(def command-input-record-schema-template
  {:type :record
   :fields []
   :label ""
   :doc ""
   :name ""
   :inputBinding nil})

(def command-line-tool-template
  {:class :CommandLineTool
   :cwlVersion "v1.2"
   :id 0
   :label ""
   :doc ""
   :requirements []
   :inputs []
   :outputs []
   :baseCommand ""
   :arguments []
   :stdin ""
   :stdout ""
   :stderr ""
   :successCodes [0]})
