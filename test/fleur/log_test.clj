(ns fleur.log-test
  "Tests for the run-feedback event layer. These capture Telemere *signals*
   directly (via `with-signals`) rather than asserting on stderr/file output, so
   they need no handler plumbing and stay fast."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [taoensso.telemere :as t]
            [fleur.log :as log]
            [fleur.process :as process]
            [fleur.preprocess :as pre]))

;; Signal *capture* is gated by the global runtime min-level (with-signals opts
;; don't lower it), so raise it to :debug for the duration and restore after.
(use-fixtures :once
  (fn [f]
    (t/set-min-level! :debug)
    (try (f) (finally (t/set-min-level! :info)))))

(defn- signals-by-id
  "Group captured signals by their :id."
  [signals]
  (group-by :id signals))

(deftest event-helpers-shape-test
  (testing "each helper emits one signal with the expected id, level, and data"
    (let [{:keys [signals]}
          (t/with-signals
            (do
              (log/workflow-start! {:name "wf" :n-steps 2})
              (log/step-start! {:step "a" :class "CommandLineTool"})
              (log/step-done! {:step "a" :run-msecs 5})
              (log/tool-failed! {:step "blastn" :exit 1 :stderr "boom"})))
          by-id (signals-by-id signals)]
      (is (= 4 (count signals)))
      (is (= :info  (:level (first (by-id :fleur.log/workflow-start)))))
      (is (= 2      (:n-steps (:data (first (by-id :fleur.log/workflow-start))))))
      (is (= :error (:level (first (by-id :fleur.log/tool-failed)))))
      (is (= 1      (:exit (:data (first (by-id :fleur.log/tool-failed)))))))))

(deftest scatter-events-test
  (testing "scatter start/done carry the task count"
    (let [{:keys [signals]}
          ;; scatter events are :debug; the :once fixture raised the floor.
          (t/with-signals
            (do
              (log/scatter-start! {:step "search" :n 5 :method nil})
              (log/scatter-done! {:step "search" :n 5 :run-msecs 20})))
          by-id (signals-by-id signals)]
      (is (= 5 (:n (:data (first (by-id :fleur.log/scatter-start))))))
      (is (= 5 (:n (:data (first (by-id :fleur.log/scatter-done)))))))))

(deftest workflow-run-emits-lifecycle-test
  (testing "running a real (non-Docker) workflow emits workflow + per-step events"
    (let [wf (pre/preprocess-file "resources/linear_math.cwl" {:backend :cwljava})
          {:keys [value signals]}
          (t/with-signals (process/run wf {:x 5}))
          by-id (signals-by-id signals)]
      ;; linear_math is a 2-step ExpressionTool workflow (x -> +1 -> *2 = 11).
      (is (= 11 (:result (:boundOutputs value))))
      (is (= 1 (count (by-id :fleur.log/workflow-start))))
      (is (= 1 (count (by-id :fleur.log/workflow-done))))
      (is (pos? (count (by-id :fleur.log/step-start))))
      (is (= (count (by-id :fleur.log/step-start))
             (count (by-id :fleur.log/step-done)))
          "every started step also completes"))))

;;; ---------------------------------------------------------------------------
;;; Live progress display (pure state + rendering)
;;; ---------------------------------------------------------------------------

(defn- sig [id data] {:id id :data data})

(deftest update-progress-test
  (testing "folding a lifecycle event sequence yields the expected state"
    (let [events [[(sig :fleur.log/workflow-start {:n-steps 3}) 1000]
                  [(sig :fleur.log/step-start {:step "search" :class "CommandLineTool"}) 1000]
                  [(sig :fleur.log/scatter-start {:n 5 :method nil}) 1001]
                  [(sig :fleur.log/scatter-progress {:done 3 :n 5}) 1002]]
          mid (reduce (fn [st [s now]] (log/update-progress st s now)) {} events)]
      (is (= 3 (:total-steps mid)))
      (is (= 0 (:completed mid)))
      (is (= 1000 (:run-start mid)))
      (is (= {:step "search" :class "CommandLineTool" :scatter {:done 3 :n 5}}
             (:current mid)))
      (testing "step-done increments completed and clears current"
        (let [after (log/update-progress mid (sig :fleur.log/step-done {:step "search"}) 1003)]
          (is (= 1 (:completed after)))
          (is (nil? (:current after)))))
      (testing "workflow-done marks the run finished"
        (let [after (log/update-progress mid (sig :fleur.log/workflow-done {}) 1003)]
          (is (true? (:done? after))))))))

(deftest render-live-test
  (testing "a scattered step renders step name, tasks, step count, elapsed"
    (let [state {:total-steps 3 :completed 1 :run-start 0
                 :current {:step "search" :scatter {:done 3 :n 5}}}]
      (is (= "[fleur] ⠋   search   tasks 3/5   2/3 steps   0:04"
             (log/render-live state 4000)))))
  (testing "a non-scatter step omits the tasks segment"
    (let [state {:total-steps 3 :completed 0 :run-start 0
                 :current {:step "build_db" :scatter nil}}]
      (is (= "[fleur] ⠋   build_db   1/3 steps   0:00"
             (log/render-live state 812))))))

(deftest scatter-progress-event-test
  (testing "scatter-progress! carries the running done/total counts"
    (let [{:keys [signals]}
          (t/with-signals (log/scatter-progress! {:step "search" :done 2 :n 5}))
          s (first signals)]
      (is (= :fleur.log/scatter-progress (:id s)))
      (is (= {:step "search" :done 2 :n 5} (:data s))))))
