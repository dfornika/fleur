(ns fleur.expression-test
  (:require [clojure.test :refer [deftest testing is]]
            [fleur.expression :as expr]))

;;; ---------------------------------------------------------------------------
;;; Parameter reference parsing
;;; ---------------------------------------------------------------------------

(deftest parse-parameter-reference-test
  (testing "root symbol only"
    (is (= ["inputs"] (expr/parse-parameter-reference "inputs"))))

  (testing "dotted access"
    (is (= ["inputs" "src" "path"] (expr/parse-parameter-reference "inputs.src.path"))))

  (testing "bracketed string and numeric index"
    (is (= ["inputs" "a" 0] (expr/parse-parameter-reference "inputs.a[0]")))
    (is (= ["inputs" "odd key"] (expr/parse-parameter-reference "inputs['odd key']")))
    (is (= ["inputs" "q"] (expr/parse-parameter-reference "inputs[\"q\"]"))))

  (testing "leading/trailing whitespace is tolerated"
    (is (= ["runtime" "outdir"] (expr/parse-parameter-reference "  runtime.outdir  "))))

  (testing "non parameter references return nil"
    (is (nil? (expr/parse-parameter-reference "1 + 2")))
    (is (nil? (expr/parse-parameter-reference "inputs.x.toUpperCase()")))))

;;; ---------------------------------------------------------------------------
;;; Parameter reference evaluation (no JavaScript)
;;; ---------------------------------------------------------------------------

(def ctx
  {:runtime {:outdir "/out" :cores 4}
   :inputs {:message "hi"
            :n 42
            :src {:class "File" :path "resources/Hello.java"}
            :xs [10 20 30]}})

(deftest parameter-reference-evaluation-test
  (testing "runtime and scalar inputs"
    (is (= "/out" (expr/evaluate "$(runtime.outdir)" ctx)))
    (is (= 4 (expr/evaluate "$(runtime.cores)" ctx))))

  (testing "File path via dotted access"
    (is (= "resources/Hello.java" (expr/evaluate "$(inputs.src.path)" ctx))))

  (testing "array index"
    (is (= 20 (expr/evaluate "$(inputs.xs[1])" ctx))))

  (testing "a whole-string single expression preserves the value's type"
    (is (= 42 (expr/evaluate "$(inputs.n)" ctx)))
    (is (= {:class "File" :path "resources/Hello.java"}
           (expr/evaluate "$(inputs.src)" ctx))))

  (testing "missing fields evaluate to nil"
    (is (nil? (expr/evaluate "$(inputs.nope)" ctx)))
    (is (nil? (expr/evaluate "$(inputs.src.nope)" ctx)))))

(deftest interpolation-test
  (testing "expression embedded in text is stringified and concatenated"
    (is (= "out=/out" (expr/evaluate "out=$(runtime.outdir)" ctx)))
    (is (= "n is 42!" (expr/evaluate "n is $(inputs.n)!" ctx))))

  (testing "multiple expressions in one string"
    (is (= "/out/4" (expr/evaluate "$(runtime.outdir)/$(runtime.cores)" ctx))))

  (testing "embedded non-scalar is JSON encoded"
    (is (= "f=[10,20,30]" (expr/evaluate "f=$(inputs.xs)" ctx))))

  (testing "a string with no expression is returned unchanged"
    (is (= "just text" (expr/evaluate "just text" ctx))))

  (testing "backslash escapes an expression"
    (is (= "$(inputs.n)" (expr/evaluate "\\$(inputs.n)" ctx))))

  (testing "non-strings pass through untouched"
    (is (= 7 (expr/evaluate 7 ctx)))
    (is (= [:a] (expr/evaluate [:a] ctx)))))

(deftest parameter-reference-mode-rejects-javascript-test
  (testing "${...} needs InlineJavascriptRequirement"
    (is (thrown? clojure.lang.ExceptionInfo (expr/evaluate "${ return 1; }" ctx))))
  (testing "a non-trivial $(...) needs InlineJavascriptRequirement"
    (is (thrown? clojure.lang.ExceptionInfo (expr/evaluate "$(1 + 2)" ctx)))))

;;; ---------------------------------------------------------------------------
;;; JavaScript evaluation (InlineJavascriptRequirement -> :js? true)
;;; ---------------------------------------------------------------------------

(def js {:js? true})

(deftest javascript-expression-test
  (testing "arithmetic expression"
    (is (= 3 (expr/evaluate "$(1 + 2)" ctx js))))

  (testing "references injected as JS objects"
    (is (= "HI" (expr/evaluate "$(inputs.message.toUpperCase())" ctx js)))
    (is (= 3 (expr/evaluate "$(inputs.xs.length)" ctx js))))

  (testing "${...} function body form"
    (is (= 60 (expr/evaluate "${ return inputs.xs[0] + inputs.xs[2] + 20; }" ctx js))))

  (testing "array/object results round-trip to Clojure data"
    (is (= [20 40 60]
           (expr/evaluate "${ return inputs.xs.map(function(x){ return x*2; }); }" ctx js)))
    (is (= {:doubled 84}
           (expr/evaluate "${ return {doubled: inputs.n * 2}; }" ctx js))))

  (testing "integer-valued results come back as integers"
    (is (= 84 (expr/evaluate "$(inputs.n * 2)" ctx js)))
    (is (= 2.5 (expr/evaluate "$(5 / 2)" ctx js)))))

(deftest contains-expression?-test
  (is (true? (expr/contains-expression? "a $(inputs.x) b")))
  (is (false? (expr/contains-expression? "no expressions here")))
  (is (false? (expr/contains-expression? "\\$(escaped)")))
  (is (false? (expr/contains-expression? 42))))
