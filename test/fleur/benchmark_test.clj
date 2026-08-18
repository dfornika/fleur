(ns fleur.benchmark-test
  "Runs the benchmark corpus described by `benchmark/manifest.edn`.

   The corpus is a growing set of semi-realistic CWL tools and workflows used to
   develop Fleur against. Each case is either:

     :supported   -- must produce its `:expected` output (a regression guard); or
     :unsupported -- exercises a CWL feature not implemented yet. `:expected`
                     holds the CORRECT spec output; the test asserts Fleur does
                     NOT yet match, so the suite stays green until the feature
                     lands and then turns red -- the cue to promote the case to
                     :supported.

   See benchmark/README.md for the full story."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fleur.process :as process]))

(def ^:private manifest-path "benchmark/manifest.edn")

(defn- load-manifest []
  (edn/read-string (slurp (io/file manifest-path))))

(defn- project-value
  "Reduce a bound output value to something comparable, per a `:project` field.
   For File-map outputs, `:contents` reads and trims the produced file's text;
   any other keyword pulls that field from the map (e.g. `:basename`, `:size`).
   Non-map values (scalars, arrays) are returned unchanged."
  [v field]
  (cond
    (not (map? v))        v
    (= field :contents)   (some-> (:path v) slurp str/trim)
    :else                 (get v field)))

(defn- run-case
  "Run one benchmark case. Returns a map with `:outcome` one of :match,
   :mismatch, or :error, plus `:actual` (or `:error`). A case matches when the
   process's bound outputs agree with `:expected` on exactly the expected keys
   (extra bound outputs, e.g. File metadata, are ignored). An optional
   `:project` map {output-id field} reduces File outputs to a comparable value
   before comparison (see `project-value`)."
  [{:keys [cwl job expected project]}]
  (try
    (let [bound     (:boundOutputs (process/run-file cwl job))
          projected (reduce-kv (fn [m oid field]
                                 (cond-> m
                                   (contains? m oid) (update oid project-value field)))
                               bound
                               (or project {}))
          compare   (select-keys projected (keys expected))]
      (if (= expected compare)
        {:outcome :match :actual compare}
        {:outcome :mismatch :actual compare}))
    (catch Throwable t
      {:outcome :error :error (or (.getMessage t) (str (class t)))})))

(defn- report-line [{:keys [id status feature]} {:keys [outcome actual error]}]
  (format "  %-22s %-14s %-12s %s"
          (name id)
          (name feature)
          (name status)
          (case outcome
            :match    "= match"
            :mismatch (str "! mismatch -> " (pr-str actual))
            :error    (str "! error -> " error))))

(deftest benchmark-corpus-test
  (let [cases   (load-manifest)
        results (mapv (fn [c] [c (run-case c)]) cases)]
    (println "\n=== Fleur benchmark corpus ===")
    (doseq [[c r] results]
      (println (report-line c r)))
    (let [{:keys [supported unsupported]} (group-by :status (map first results))]
      (println (format "Supported: %d   Unsupported: %d   Total: %d\n"
                       (count supported) (count unsupported) (count cases))))

    (testing "supported cases produce their expected outputs"
      (doseq [[c r] results
              :when (= :supported (:status c))]
        (is (= :match (:outcome r))
            (format "%s (%s) should match %s but got %s"
                    (name (:id c)) (name (:feature c))
                    (pr-str (:expected c)) (pr-str (or (:actual r) (:error r)))))))

    (testing "unsupported cases do NOT yet match (promote to :supported when they do)"
      (doseq [[c r] results
              :when (= :unsupported (:status c))]
        (is (not= :match (:outcome r))
            (format "%s (%s) now produces the correct output %s -- promote it to :status :supported in %s"
                    (name (:id c)) (name (:feature c))
                    (pr-str (:expected c)) manifest-path))))))
