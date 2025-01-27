(ns fleur.docker
  (:require [clojure.java.shell :as shell]))

(defn docker-pull
  ""
  [image-name]
  (apply shell/sh ["docker" "pull" image-name]))
