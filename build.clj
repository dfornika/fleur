(ns build
  "tools.build script. Build the standalone (uber) jar with:

     clojure -T:build uber

   producing target/cwl-runner-<version>-standalone.jar (main class
   fleur.main). See https://clojure.org/guides/tools_build."
  (:require [clojure.tools.build.api :as b]))

(def lib 'fleur/cwl-runner)
(def version "0.1.0")
(def main 'fleur.main)
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :ns-compile [main]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main main}))
