(ns fleur.staging
  "Input file handling: resolving File/Directory paths to absolute locations
   (and populating CWL File metadata), staging inputs into a working directory,
   and processing `InitialWorkDirRequirement`.

   By default CWL leaves input files where they are and refers to them by
   absolute path (so a tool running in `runtime.outdir` still finds them);
   `resolve-inputs` implements that. `stage-inputs!` additionally copies (or
   symlinks) inputs into the working directory, and `initial-work-dir!` stages
   the entries listed by `InitialWorkDirRequirement`."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [fleur.expression :as expr]
            [fleur.runtime :as rt])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;;; ---------------------------------------------------------------------------
;;; File metadata
;;; ---------------------------------------------------------------------------

(defn- split-ext
  "Split a basename into [nameroot nameext]. The extension is the last dot and
   what follows, unless the only dot is a leading one (a dotfile)."
  [basename]
  (let [i (.lastIndexOf basename ".")]
    (if (pos? i)
      [(subs basename 0 i) (subs basename i)]
      [basename ""])))

(defn- strip-file-uri [s]
  (if (and (string? s) (.startsWith s "file://"))
    (subs s (count "file://"))
    s))

(defn- absolutize
  "Absolute path string for `p`, resolved against `base` when `p` is relative."
  [base p]
  (let [f (io/file (strip-file-uri p))]
    (.getPath (.getAbsoluteFile (if (.isAbsolute f) f (io/file base p))))))

(defn file-object?
  "True if `v` is a CWL File or Directory object."
  [v]
  (and (map? v) (contains? #{"File" "Directory"} (:class v))))

(defn resolve-file
  "Absolutize a File/Directory object's `:path` against `base-dir` and populate
   CWL metadata (basename, dirname, nameroot/nameext for Files, size when the
   file exists). Recurses into `:secondaryFiles` and Directory `:listing`.
   Non-File values pass through unchanged."
  [base-dir obj]
  (if-not (file-object? obj)
    obj
    (let [p (or (:path obj) (:location obj))
          abs (when p (absolutize base-dir p))
          f (when abs (io/file abs))
          bn (when f (.getName f))
          [nr ne] (if bn (split-ext bn) [nil ""])]
      (cond-> obj
        abs                     (assoc :path abs
                                       :basename bn
                                       :dirname (some-> (.getParent f) str))
        (= "File" (:class obj)) (assoc :nameroot nr :nameext ne)
        (and f (.isFile f))     (assoc :size (.length f))
        (:secondaryFiles obj)   (update :secondaryFiles #(mapv (partial resolve-file base-dir) %))
        (:listing obj)          (update :listing #(mapv (partial resolve-file base-dir) %))))))

(defn- resolve-value [base v]
  (cond
    (file-object? v) (resolve-file base v)
    (sequential? v)  (mapv #(resolve-value base %) v)
    :else            v))

(defn resolve-inputs
  "Resolve every File/Directory in the tool's input values to an absolute path
   with populated metadata, relative to `base-dir`."
  [tool base-dir]
  (update tool :inputs
          (fn [inputs]
            (into (empty inputs)
                  (map (fn [[k input]]
                         [k (update input :value #(resolve-value base-dir %))])
                       inputs)))))

(def load-contents-limit
  "CWL `loadContents` applies to files 64 KiB or smaller."
  65536)

(defn load-contents
  "Read the entire contents of a UTF-8 text file for CWL `loadContents`. The file
   must be 64 KiB or smaller; a larger file raises a fatal error (CWL v1.2:
   `loadContents` must fail rather than silently truncate)."
  [path]
  (let [f   (io/file path)
        len (.length f)]
    (when (> len load-contents-limit)
      (throw (ex-info (str "loadContents: file is larger than the 64 KiB limit: "
                           path " (" len " bytes)")
                      {:path path :size len :limit load-contents-limit})))
    (slurp f :encoding "UTF-8")))

(defn- input-loads-contents?
  "True when an input spec requests `loadContents`, either at the top level
   (CWL v1.1+) or on its `inputBinding` (CWL v1.0)."
  [input]
  (boolean (or (:loadContents input)
               (get-in input [:inputBinding :loadContents]))))

(defn load-contents-inputs
  "Attach `:contents` (first 64 KiB) to each File input whose spec requests
   `loadContents`. Run after `resolve-inputs` so `:path` is absolute."
  [tool]
  (update tool :inputs
          (fn [inputs]
            (into (empty inputs)
                  (map (fn [[k input]]
                         [k (let [v (:value input)]
                              (if (and (input-loads-contents? input)
                                       (file-object? v) (:path v))
                                (assoc-in input [:value :contents] (load-contents (:path v)))
                                input))])
                       inputs)))))

;;; ---------------------------------------------------------------------------
;;; Staging into a working directory
;;; ---------------------------------------------------------------------------

(defn- copy-tree! [^java.io.File src ^java.io.File dest]
  (.mkdirs dest)
  (doseq [child (.listFiles src)]
    (let [d (io/file dest (.getName child))]
      (if (.isDirectory child)
        (copy-tree! child d)
        (io/copy child d)))))

(defn stage-file!
  "Stage a File/Directory `obj` into `target-dir`, returning the object updated
   to its new location. `:method` is `:copy` (default) or `:symlink`;
   `:entryname` overrides the staged name. secondaryFiles are staged alongside."
  ([target-dir obj] (stage-file! target-dir obj {}))
  ([target-dir obj {:keys [method entryname] :or {method :copy}}]
   (let [src (io/file (:path obj))
         name (or entryname (:basename obj) (.getName src))
         dest (io/file target-dir name)]
     (io/make-parents dest)
     (cond
       (= method :symlink)
       (do (when (.exists dest) (.delete dest))
           (Files/createSymbolicLink (.toPath dest) (.toPath src)
                                     (make-array FileAttribute 0)))
       (= "Directory" (:class obj)) (copy-tree! src dest)
       :else                        (io/copy src dest))
     (cond-> (resolve-file target-dir (assoc obj :path (.getPath dest)
                                             :location nil :basename name))
       (:secondaryFiles obj)
       (assoc :secondaryFiles
              (mapv #(stage-file! target-dir % {:method method}) (:secondaryFiles obj)))))))

(defn stage-inputs!
  "Copy (or symlink, via `:method`) every File/Directory input into
   `target-dir`, returning the tool with input values pointing at the staged
   copies. Use when a tool expects its inputs present in the working directory."
  ([tool target-dir] (stage-inputs! tool target-dir {}))
  ([tool target-dir opts]
   (let [stage (fn stage [v]
                 (cond
                   (file-object? v) (stage-file! target-dir v opts)
                   (sequential? v)  (mapv stage v)
                   :else            v))]
     (update tool :inputs
             (fn [inputs]
               (into (empty inputs)
                     (map (fn [[k input]] [k (update input :value stage)]) inputs)))))))

;;; ---------------------------------------------------------------------------
;;; InitialWorkDirRequirement
;;; ---------------------------------------------------------------------------

(defn- eval-str [v context js?]
  (if (and context (string? v)) (expr/evaluate v context {:js? js?}) v))

(defn- stage-entry!
  "Stage one InitialWorkDirRequirement listing entry into `outdir`."
  [outdir entry context js?]
  (cond
    ;; an expression yielding a File/Directory, or a list of them
    (string? entry)
    (let [v (eval-str entry context js?)]
      (doseq [e (if (sequential? v) v [v])]
        (when (file-object? e) (stage-file! outdir e))))

    ;; a literal File/Directory object
    (file-object? entry)
    (stage-file! outdir entry)

    ;; a Dirent: {:entryname .. :entry <File|Directory|content>}
    (and (map? entry) (contains? entry :entry))
    (let [entryname (eval-str (:entryname entry) context js?)
          e (eval-str (:entry entry) context js?)]
      (cond
        (file-object? e) (stage-file! outdir e {:entryname entryname})
        :else            (spit (io/file outdir entryname)
                               (if (string? e) e (json/write-str e)))))

    :else nil))

(defn initial-work-dir!
  "Process the tool's `InitialWorkDirRequirement` (if any), staging its
   `:listing` entries into `outdir`. Returns `outdir`."
  ([tool outdir] (initial-work-dir! tool outdir nil false))
  ([tool outdir context js?]
   (when-let [req (rt/find-requirement tool "InitialWorkDirRequirement")]
     (let [listing (:listing req)
           listing (if (string? listing) (eval-str listing context js?) listing)]
       (doseq [entry (if (sequential? listing) listing [listing])]
         (stage-entry! outdir entry context js?))))
   outdir))
