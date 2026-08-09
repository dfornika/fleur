(ns fleur.docker
  "Docker execution support for CommandLineTools.

   When a tool declares a `DockerRequirement`, its command runs inside a
   container. This namespace provides the pieces: resolving the image,
   planning read-only input mounts plus a read-write output/tmp mount, remapping
   input File paths to their in-container locations (so the command line uses
   container paths), and assembling the `docker run` argv. The orchestration
   lives in `fleur.command-line-tool/run`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [fleur.runtime :as rt]
            [fleur.staging :as stg]))

(def default-container-outdir "/var/spool/cwl")
(def default-container-tmpdir "/tmp")
(def ^:private inputs-root "/var/lib/cwl/inputs")

(defn docker-requirement
  "The tool's DockerRequirement (from requirements or hints), or nil."
  [tool]
  (rt/find-requirement tool "DockerRequirement"))

(defn dockerized?
  "True if the tool declares a DockerRequirement."
  [tool]
  (boolean (docker-requirement tool)))

(defn docker-image
  "The image reference to run: `dockerImageId` if present, else `dockerPull`."
  [req]
  (or (:dockerImageId req) (:dockerPull req)))

(defn container-outdir
  "The designated output directory inside the container."
  [req]
  (or (:dockerOutputDirectory req) default-container-outdir))

;;; ---------------------------------------------------------------------------
;;; Image management
;;; ---------------------------------------------------------------------------

(defn docker-pull
  "Pull an image by name."
  [image-name]
  (shell/sh "docker" "pull" image-name))

(defn image-present?
  "True if `image` is available in the local Docker image store."
  [image]
  (zero? (:exit (shell/sh "docker" "image" "inspect" image))))

(defn ensure-image!
  "Ensure the image for `req` is available locally, pulling it when a
   `dockerPull` is given and the image is not already present. Returns the image
   reference to use with `docker run`."
  [req]
  (let [image (docker-image req)]
    (when (and (:dockerPull req) (not (image-present? image)))
      (docker-pull (:dockerPull req)))
    image))

;;; ---------------------------------------------------------------------------
;;; Mount planning and path remapping
;;; ---------------------------------------------------------------------------

(defn- primary-files
  "All File/Directory objects directly held by a value (scalar or array), not
   descending into their secondaryFiles."
  [v]
  (cond
    (stg/file-object? v) [v]
    (sequential? v)      (mapcat primary-files v)
    :else                nil))

(defn plan-mounts
  "Plan the container volume mounts for `tool` (inputs resolved to host absolute
   paths). Returns `{:mounts [{:host :container :mode}] :path-map {host->container}}`.

   Each input File/Directory (and its secondaryFiles, co-located with the
   primary) is mounted read-only under `/var/lib/cwl/inputs/<n>/`; the host
   outdir and tmpdir are mounted read-write at the container outdir/tmpdir."
  [tool host-outdir host-tmpdir c-outdir c-tmpdir]
  (let [primaries (->> (:inputs tool)
                       (sort-by (comp name key))
                       (mapcat (fn [[_ input]] (primary-files (:value input)))))
        add-file (fn [acc dir obj]
                   (let [cpath (str dir "/" (:basename obj))]
                     (-> acc
                         (update :mounts conj {:host (:path obj) :container cpath :mode "ro"})
                         (update :path-map assoc (:path obj) cpath))))
        base (reduce (fn [acc [idx obj]]
                       (let [dir (str inputs-root "/" idx)]
                         (reduce (fn [a sf] (add-file a dir sf))
                                 (add-file acc dir obj)
                                 (:secondaryFiles obj))))
                     {:mounts [] :path-map {}}
                     (map-indexed vector primaries))]
    (-> base
        (update :mounts conj {:host host-outdir :container c-outdir :mode "rw"})
        (update :mounts conj {:host host-tmpdir :container c-tmpdir :mode "rw"}))))

(defn- remap-value [v path-map]
  (cond
    (stg/file-object? v)
    (let [cpath (get path-map (:path v))]
      (cond-> v
        cpath                 (assoc :path cpath
                                     :dirname (some-> (io/file cpath) .getParent str))
        (:secondaryFiles v)   (update :secondaryFiles #(mapv (fn [sf] (remap-value sf path-map)) %))))

    (sequential? v) (mapv #(remap-value % path-map) v)
    :else           v))

(defn remap-tool-paths
  "Rewrite the tool's input File/Directory `:path` (and secondaryFiles) to their
   in-container locations, per `path-map`. basename is preserved; dirname is
   updated to the container directory."
  [tool path-map]
  (update tool :inputs
          (fn [inputs]
            (into (empty inputs)
                  (map (fn [[k input]]
                         [k (update input :value #(remap-value % path-map))])
                       inputs)))))

;;; ---------------------------------------------------------------------------
;;; docker run argv
;;; ---------------------------------------------------------------------------

(defn- volume-arg [{:keys [host container mode]}]
  (str host ":" container (when (= mode "ro") ":ro")))

(defn docker-run-argv
  "Assemble the `docker run` argv wrapping `command` (a seq of tokens): remove
   the container on exit, keep stdin open, bind each mount, set the working
   directory, and use `image`."
  [image mounts workdir command]
  (vec (concat ["docker" "run" "--rm" "-i"]
               (mapcat (fn [m] ["-v" (volume-arg m)]) mounts)
               ["-w" workdir image]
               command)))
