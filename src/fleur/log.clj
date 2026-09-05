(ns fleur.log
  "Run feedback / structured logging for Fleur, built on Telemere.

   Two sinks, configured by `init!`:

   - a **minimal, human-friendly** view on **stderr** (so the CLI's result JSON
     stays clean on stdout), showing workflow/step/scatter lifecycle with
     durations; and
   - an optional **detailed EDN log file** carrying the full structured signals
     (argv, tool stderr, mounts, ...).

   All Telemere calls live here so the rest of the codebase logs through a small,
   stable event vocabulary (`workflow-start!`, `step-done!`, `tool-failed!`, ...).
   Each event carries a concise `:msg` (what the human sees) plus structured
   `:data` (what the file/tools see)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;;; ---------------------------------------------------------------------------
;;; Handler setup
;;; ---------------------------------------------------------------------------

(defn- human-output-fn
  "Format a signal as a single minimal line: `[fleur] <msg>`. Each event's :msg
   carries its own leading marker (▶ ✓ – ✗ $ ...). Structured :data is
   intentionally omitted here (it lives in the EDN log file)."
  [signal]
  (str "[fleur] " (force (:msg_ signal)) \newline))

(defn init!
  "Configure logging sinks. Options:

   - `:log-file`      path for the detailed EDN log (nil = no file sink)
   - `:console-level` min level for the stderr view (default `:info`)
   - `:file-level`    min level for the log file (default `:debug`)

   Replaces Telemere's default stdout handler with a stderr console handler and
   (when `:log-file` is set) an EDN file handler. Idempotent enough for CLI use;
   call once at startup."
  ([] (init! {}))
  ([{:keys [log-file console-level file-level]
     :or   {console-level :info file-level :debug}}]
   ;; The global floor must admit the lowest-level sink; per-handler :min-level
   ;; then gates each sink independently. A file sink defaults to :debug, so the
   ;; floor drops to :debug whenever a file is configured.
   (t/set-min-level! (if log-file file-level console-level))
   (t/remove-handler! :default/console)
   (t/add-handler! :fleur/console
                   (t/handler:console {:stream (io/writer System/err)
                                       :output-fn human-output-fn})
                   {:min-level console-level})
   (when log-file
     (let [f (io/file log-file)]
       (io/make-parents f)                ; create the parent dir if it's missing
       (t/add-handler! :fleur/file
                       (t/handler:console {:stream (io/writer f :append true)
                                           :output-fn (t/pr-signal-fn {:pr-fn :edn})})
                       {:min-level file-level})))
   nil))

(defn shutdown!
  "Flush and stop all handlers. Call before the process exits so async/buffered
   output reaches stderr and the log file."
  []
  (t/stop-handlers!))

;;; ---------------------------------------------------------------------------
;;; Timing
;;; ---------------------------------------------------------------------------

(defn now-nanos [] (System/nanoTime))

(defn msecs-since
  "Elapsed milliseconds since a `now-nanos` reading, rounded to the ms."
  [start-nanos]
  (Math/round (/ (double (- (System/nanoTime) start-nanos)) 1e6)))

(defn- fmt-dur
  "Human duration: `820ms` or `3.2s`."
  [msecs]
  (if (< msecs 1000)
    (str msecs "ms")
    (format "%.1fs" (/ (double msecs) 1000.0))))

;;; ---------------------------------------------------------------------------
;;; Event vocabulary
;;; ---------------------------------------------------------------------------

(defn workflow-start! [{:keys [name n-steps] :as data}]
  (t/event! ::workflow-start
            {:level :info :data data
             :msg (str "workflow " (or name "?") " · " n-steps " steps")}))

(defn workflow-done! [{:keys [name run-msecs] :as data}]
  (t/event! ::workflow-done
            {:level :info :data data
             :msg (str "workflow " (or name "?") " done (" (fmt-dur run-msecs) ")")}))

(defn step-start! [{:keys [step class] :as data}]
  (t/event! ::step-start
            {:level :info :data data
             :msg (str "▶ " step (when class (str " (" class ")")))}))

(defn step-done! [{:keys [step run-msecs n-tasks] :as data}]
  (t/event! ::step-done
            {:level :info :data data
             :msg (str "✓ " step
                       (when n-tasks (str " · " n-tasks " task" (when (not= 1 n-tasks) "s")))
                       " (" (fmt-dur run-msecs) ")")}))

(defn step-skipped! [{:keys [step] :as data}]
  (t/event! ::step-skipped
            {:level :info :data data
             :msg (str "– " step " (skipped: when=false)")}))

(defn scatter-start! [{:keys [step n method] :as data}]
  (t/event! ::scatter-start
            {:level :debug :data data
             :msg (str "scatter " step ": " n " task" (when (not= 1 n) "s")
                       (when method (str " (" (clojure.core/name method) ")")))}))

(defn scatter-done! [{:keys [step n run-msecs] :as data}]
  (t/event! ::scatter-done
            {:level :debug :data data
             :msg (str "scatter " step " done: " n " task" (when (not= 1 n) "s")
                       " (" (fmt-dur run-msecs) ")")}))

(defn command! [{:keys [step argv] :as data}]
  (t/event! ::command
            {:level :debug :data data
             :msg (str "$ " (str/join " " argv))}))

(defn tool-stderr! [{:keys [step stderr] :as data}]
  (t/event! ::tool-stderr
            {:level :debug :data data
             :msg (str "stderr[" step "]: " (first (str/split-lines (str/trim stderr))))}))

(defn tool-failed! [{:keys [step exit] :as data}]
  (t/event! ::tool-failed
            {:level :error :data data
             :msg (str "✗ " step " exited " exit)}))

(defn image-pull! [{:keys [image] :as data}]
  (t/event! ::image-pull
            {:level :info :data data
             :msg (str "pulling image " image)}))

;; Fleur is quiet by default: drop Telemere's built-in stdout handler as soon as
;; this namespace loads, so merely requiring Fleur never prints to stdout (which
;; carries the CLI's result JSON) or pollutes test output. Signals are still
;; *created* (so `with-signals` capture works in tests); enable real sinks
;; explicitly via `init!`.
(defonce ^:private _quiet-by-default
  (do (t/remove-handler! :default/console) nil))
