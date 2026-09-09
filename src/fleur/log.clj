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

;;; ---------------------------------------------------------------------------
;;; Live progress display (TTY)
;;;
;;; A stateful handler that renders a nextflow-style live view to stderr:
;;; finished steps print permanent lines that scroll up, while a single live
;;; line at the bottom tracks the in-progress step. The state model and line
;;; rendering are pure (and unit-tested); the handler is a thin I/O wrapper.
;;; ---------------------------------------------------------------------------

(def ^:private erase-line "\r[K")   ; CR + clear-to-end-of-line

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn update-progress
  "Fold one signal into the progress state. Pure. `now` is epoch millis.
   State: {:total-steps :completed :current {:step :class :scatter {:done :n}}
           :run-start :done?}."
  [state signal now]
  (let [d (:data signal)]
    (case (:id signal)
      ::workflow-start   (assoc state :total-steps (:n-steps d) :completed 0
                                :current nil :run-start now :done? false)
      ::step-start       (assoc state :current {:step (:step d) :class (:class d)
                                                :scatter nil})
      ::scatter-start    (assoc-in state [:current :scatter] {:done 0 :n (:n d)})
      ::scatter-progress (assoc-in state [:current :scatter] {:done (:done d) :n (:n d)})
      (::step-done
       ::step-skipped)   (-> state (update :completed (fnil inc 0)) (assoc :current nil))
      ::workflow-done    (assoc state :done? true :current nil)
      state)))

(defn- fmt-elapsed [ms]
  (let [s (quot ms 1000)] (format "%d:%02d" (quot s 60) (mod s 60))))

(defn render-live
  "Render the live bottom line for `state` at `now` (epoch millis). Assumes a
   current step is set. Returns a string with no trailing newline."
  [state now]
  (let [elapsed (max 0 (- now (:run-start state 0)))
        frame   (nth spinner-frames (mod (quot elapsed 80) (count spinner-frames)))
        {:keys [step scatter]} (:current state)
        total   (:total-steps state)
        idx     (if total (min total (inc (:completed state 0))) (inc (:completed state 0)))
        parts   (cond-> [frame (str step)]
                  scatter (conj (str "tasks " (:done scatter) "/" (:n scatter)))
                  true    (conj (str idx (when total (str "/" total)) " steps"))
                  true    (conj (fmt-elapsed elapsed)))]
    (str "[fleur] " (str/join "   " parts))))

(def ^:private permanent-ids
  "Event ids that print a permanent (scrollback) line in progress mode; other
   events only update the live line (or are file-only)."
  #{::workflow-start ::step-done ::step-skipped ::image-pull ::tool-failed
    ::workflow-done})

(defn progress-handler
  "A Telemere handler fn that renders live progress to `writer` (stderr).
   0-arity (stop) clears any live line so the cursor ends on a clean row."
  [writer]
  (let [state (atom {:live? false})]
    (fn
      ([]
       (when (:live? @state)
         (.write writer erase-line)
         (.flush writer)
         (swap! state assoc :live? false)))
      ([signal]
       (let [now  (System/currentTimeMillis)
             perm (when (permanent-ids (:id signal)) (force (:msg_ signal)))]
         (when (:live? @state) (.write writer erase-line))
         (when perm (.write writer (str "[fleur] " perm "\n")))
         (swap! state update-progress signal now)
         (let [s @state]
           (if (or (:done? s) (nil? (:current s)))
             (swap! state assoc :live? false)
             (do (.write writer (render-live s now))
                 (swap! state assoc :live? true))))
         (.flush writer))))))

(defn init!
  "Configure logging sinks. Options:

   - `:log-file`      path for the detailed EDN log (nil = no file sink)
   - `:console-level` min level for the stderr view (default `:info`)
   - `:file-level`    min level for the log file (default `:debug`)
   - `:progress?`     when true, render a live progress display on stderr (a
                      scrolling list of finished steps plus one live status line)
                      instead of the plain per-event line view. The live view
                      needs the :debug scatter events, so the global floor drops
                      to :debug when it's on.

   Replaces Telemere's default stdout handler with a stderr console/progress
   handler and (when `:log-file` is set) an EDN file handler. Idempotent enough
   for CLI use; call once at startup."
  ([] (init! {}))
  ([{:keys [log-file console-level file-level progress?]
     :or   {console-level :info file-level :debug}}]
   ;; The global floor must admit the lowest-level sink; per-handler :min-level
   ;; then gates each sink independently. A file sink or the progress view both
   ;; need :debug, so the floor drops to :debug when either is on.
   (t/set-min-level! (if (or log-file progress?) :debug console-level))
   (t/remove-handler! :default/console)
   ;; Progress view and the plain line view are mutually exclusive on stderr, so
   ;; output never doubles.
   (if progress?
     (t/add-handler! :fleur/progress
                     (progress-handler (io/writer System/err))
                     {:min-level :debug :async false})
     (t/add-handler! :fleur/console
                     (t/handler:console {:stream (io/writer System/err)
                                         :output-fn human-output-fn})
                     {:min-level console-level}))
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

(defn scatter-progress! [{:keys [step done n] :as data}]
  (t/event! ::scatter-progress
            {:level :debug :data data
             :msg (str "scatter " step ": " done "/" n)}))

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
