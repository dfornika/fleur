(ns fleur.runtime
  "Construction of the CWL `runtime` object exposed to expressions as
   `runtime.*` (outdir, tmpdir, cores, ram, ...). See CommandLineTool.html
   §runtime and Process.html."
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn find-requirement
  "Return the requirement/hint of the given class name (e.g.
   \"ResourceRequirement\") from a tool, or nil. Supports both the
   list-of-maps and map-keyed-by-class shapes."
  [tool class-name]
  (let [lookup (fn [reqs]
                 (cond
                   (map? reqs)        (get reqs (keyword class-name))
                   (sequential? reqs) (first (filter #(= class-name (some-> (:class %) name))
                                                     reqs))
                   :else              nil))]
    (or (lookup (:requirements tool))
        (lookup (:hints tool)))))

(defn- create-temp-dir [prefix]
  (-> (Files/createTempDirectory prefix (make-array FileAttribute 0))
      .toAbsolutePath
      str))

(def ^:private default-ram
  "Default reserved RAM in mebibytes when no ResourceRequirement applies.
   (The CWL default `ramMin` is 256 MiB.)"
  256)

(defn make-runtime
  "Build a CWL `runtime` object for `tool`.

   Options (all optional): `:outdir`, `:tmpdir` (created as fresh temp
   directories when omitted), `:cores`, `:ram`. `cores`/`ram` otherwise come
   from a ResourceRequirement (coresMin/coresMax, ramMin/ramMax) when those are
   numeric, falling back to the number of available processors and 256 MiB."
  ([tool] (make-runtime tool {}))
  ([tool {:keys [outdir tmpdir cores ram]}]
   (let [rr (find-requirement tool "ResourceRequirement")
         numeric (fn [& vs] (first (filter number? vs)))]
     {:outdir (or outdir (create-temp-dir "fleur-out"))
      :tmpdir (or tmpdir (create-temp-dir "fleur-tmp"))
      :cores  (or cores
                  (numeric (:coresMin rr) (:coresMax rr))
                  (.availableProcessors (Runtime/getRuntime)))
      :ram    (or ram
                  (numeric (:ramMin rr) (:ramMax rr))
                  default-ram)
      :outdirSize nil
      :tmpdirSize nil})))
