(ns fleur.expression
  "Evaluation of CWL parameter references and expressions.

   CWL defines two flavours of expression, both introduced by `$`:

   - **Parameter references** — `$(...)` whose body is a restricted grammar:
     a root symbol (`inputs`, `self`, `runtime`, ...) followed by `.field`,
     `['field']`, `[\"field\"]`, or `[index]` accessors. These are evaluated by
     a small pure-Clojure walker and require no JavaScript engine.

   - **JavaScript expressions** — `$(...)` (an expression) and `${...}` (a
     function body) evaluated as ECMAScript 5.1. Per the spec these are only
     allowed when `InlineJavascriptRequirement` is in effect. We evaluate them
     with Mozilla Rhino, a pure-Java engine.

   A string may contain several expressions mixed with literal text. If the
   whole string is a single expression the evaluated value keeps its type;
   otherwise each result is coerced to a string and concatenated. `\\$(` and
   `\\${` are literal escapes."
  (:require [clojure.string :as str]
            [clojure.data.json :as json])
  (:import [org.mozilla.javascript Context Scriptable ScriptableObject
            NativeArray Undefined]))

;;; ---------------------------------------------------------------------------
;;; Tokenizing: split a string into literal text and expression tokens
;;; ---------------------------------------------------------------------------

(defn- matching-end
  "Index of the delimiter closing the one opened at `start` in `s`. Respects
   nesting and single/double-quoted string literals (so delimiters inside JS
   strings are not counted)."
  [s start open close]
  (let [n (count s)]
    (loop [i (inc start), depth 1, quote nil]
      (when (>= i n)
        (throw (ex-info "Unterminated CWL expression" {:string s :start start})))
      (let [c (nth s i)]
        (cond
          quote          (cond
                           (= c \\)    (recur (+ i 2) depth quote)
                           (= c quote) (recur (inc i) depth nil)
                           :else       (recur (inc i) depth quote))
          (or (= c \') (= c \")) (recur (inc i) depth c)
          (= c open)     (recur (inc i) (inc depth) nil)
          (= c close)    (if (= depth 1) i (recur (inc i) (dec depth) nil))
          :else          (recur (inc i) depth nil))))))

(defn tokenize
  "Split `s` into a vector of tokens: literal strings, and expression maps
   `{:kind :paren|:brace :body <inner>}`. `\\$` is unescaped to a literal `$`."
  [s]
  (let [n (count s)]
    (loop [i 0, acc [], buf (StringBuilder.)]
      (if (>= i n)
        (if (pos? (.length buf)) (conj acc (.toString buf)) acc)
        (let [c (nth s i)]
          (cond
            ;; escaped "\$" -> literal "$"
            (and (= c \\) (< (inc i) n) (= (nth s (inc i)) \$))
            (do (.append buf \$) (recur (+ i 2) acc buf))

            ;; start of an expression: $( or ${
            (and (= c \$) (< (inc i) n) (#{\( \{} (nth s (inc i))))
            (let [open  (nth s (inc i))
                  close (if (= open \() \) \})
                  end   (matching-end s (inc i) open close)
                  body  (subs s (+ i 2) end)
                  acc   (if (pos? (.length buf)) (conj acc (.toString buf)) acc)]
              (recur (inc end)
                     (conj acc {:kind (if (= open \() :paren :brace) :body body})
                     (StringBuilder.)))

            :else (do (.append buf c) (recur (inc i) acc buf))))))))

;;; ---------------------------------------------------------------------------
;;; Parameter references (pure Clojure, no JS engine)
;;; ---------------------------------------------------------------------------

(def ^:private parameter-reference-re
  #"[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*|\[(?:[0-9]+|'[^']*'|\"[^\"]*\")\])*")

(def ^:private segment-re
  #"\.([a-zA-Z_][a-zA-Z0-9_]*)|\[([0-9]+)\]|\['([^']*)'\]|\[\"([^\"]*)\"\]")

(defn parse-parameter-reference
  "If `body` is a CWL parameter reference, return `[root & accessors]` where
   accessors are field-name strings or integer indices. Otherwise return nil."
  [body]
  (let [body (str/trim body)]
    (when (re-matches parameter-reference-re body)
      (let [root (re-find #"^[a-zA-Z_][a-zA-Z0-9_]*" body)
            accessors (map (fn [[_ dot idx sq dq]]
                             (cond
                               dot dot
                               idx (Long/parseLong idx)
                               sq  sq
                               dq  dq))
                           (re-seq segment-re body))]
        (into [root] accessors)))))

(defn- access
  "Follow one accessor into a value: field name into a map (string or keyword
   key), or integer index into a sequential."
  [node key]
  (cond
    (nil? node)        nil
    (integer? key)     (when (sequential? node) (nth (vec node) key nil))
    (map? node)        (let [k (if (keyword? key) key (keyword key))]
                         (if (contains? node k) (get node k) (get node key)))
    :else              nil))

(defn eval-parameter-reference
  "Evaluate `[root & accessors]` against `context` (a map keyed by keyword,
   e.g. {:inputs .. :self .. :runtime ..}). Missing fields yield nil (null)."
  [context accessors]
  (let [[root & more] accessors]
    (reduce access (get context (keyword root)) more)))

;;; ---------------------------------------------------------------------------
;;; JavaScript evaluation (Mozilla Rhino, ECMAScript 5.1+)
;;; ---------------------------------------------------------------------------

(defn- clj->js
  "Convert a Clojure value into something Rhino can hold in `scope`."
  [^Context ctx ^Scriptable scope v]
  (cond
    (map? v)        (let [o (.newObject ctx scope)]
                      (doseq [[k val] v]
                        (ScriptableObject/putProperty o (name k) (clj->js ctx scope val)))
                      o)
    (sequential? v) (let [arr (.newArray ctx scope (int (count v)))]
                      (doseq [[i val] (map-indexed vector v)]
                        (ScriptableObject/putProperty arr (int i) (clj->js ctx scope val)))
                      arr)
    (keyword? v)    (name v)
    ;; JS numbers are IEEE doubles; integer-valued doubles print without a
    ;; decimal point, and js->clj re-integerizes on the way out.
    (number? v)     (double v)
    :else           v))                 ; String, Boolean, or nil (JS null)

(defn- js->clj
  "Convert a Rhino result back into Clojure data."
  [v]
  (cond
    (nil? v)                     nil
    (instance? Undefined v)      nil
    (instance? CharSequence v)   (str v)
    (instance? Boolean v)        v
    (instance? Number v)         (let [d (double v)]
                                   (if (and (Double/isFinite d) (== d (Math/floor d)))
                                     (long d)
                                     d))
    (instance? NativeArray v)    (let [^NativeArray a v]
                                   (mapv #(js->clj (ScriptableObject/getProperty a (int %)))
                                         (range (.getLength a))))
    (instance? Scriptable v)     (let [^Scriptable o v]
                                   (into {}
                                         (for [id (.getIds o)]
                                           [(keyword (str id))
                                            (js->clj (ScriptableObject/getProperty o (str id)))])))
    :else                        v))

(defn eval-js
  "Evaluate a CWL JavaScript expression `body` against `context`. When
   `function-body?` (the `${...}` form) the body is wrapped and invoked."
  [context body function-body?]
  (let [ctx (Context/enter)]
    (try
      (.setOptimizationLevel ctx -1)    ; interpreter mode: portable, no bytecode
      (.setLanguageVersion ctx Context/VERSION_ES6)
      (let [scope (.initStandardObjects ctx)]
        (doseq [[k v] context]
          (ScriptableObject/putProperty scope (name k) (clj->js ctx scope v)))
        (let [src (if function-body?
                    (str "(function() {" body "})();")
                    body)]
          (js->clj (.evaluateString ctx scope src "cwl-expression" 1 nil))))
      (catch org.mozilla.javascript.RhinoException e
        (throw (ex-info (str "Error evaluating CWL expression: " (.getMessage e))
                        {:body body :function-body? function-body?} e)))
      (finally
        (Context/exit)))))

;;; ---------------------------------------------------------------------------
;;; Top-level evaluation / interpolation
;;; ---------------------------------------------------------------------------

(defn- stringify
  "Coerce an evaluated value for embedding inside a larger string. Non-scalars
   are JSON-encoded, per CWL string interpolation."
  [v]
  (cond
    (nil? v)                      "null"
    (string? v)                   v
    (or (map? v) (sequential? v)) (json/write-str v)
    :else                         (str v)))

(defn- eval-token
  [{:keys [kind body]} context js?]
  (if js?
    (eval-js context body (= kind :brace))
    (if (= kind :brace)
      (throw (ex-info "${...} requires InlineJavascriptRequirement" {:body body}))
      (if-let [accessors (parse-parameter-reference body)]
        (eval-parameter-reference context accessors)
        (throw (ex-info "Non-trivial $(...) expression requires InlineJavascriptRequirement"
                        {:body body}))))))

(defn evaluate
  "Evaluate a CWL value `v` against `context` ({:inputs .. :self .. :runtime ..}).

   Non-strings are returned unchanged. Strings are interpolated: with no
   expressions the (unescaped) string is returned; a string that is exactly one
   expression returns the raw value (type preserved); a string mixing text and
   expressions returns the concatenation with each result stringified.

   opts: `:js?` true enables full JavaScript (InlineJavascriptRequirement)."
  ([v context] (evaluate v context nil))
  ([v context {:keys [js?] :or {js? false}}]
   (if-not (string? v)
     v
     (let [tokens (tokenize v)]
       (cond
         (every? string? tokens)
         (apply str tokens)

         (and (= 1 (count tokens)) (map? (first tokens)))
         (eval-token (first tokens) context js?)

         :else
         (apply str (map #(if (string? %) % (stringify (eval-token % context js?)))
                         tokens)))))))

(defn contains-expression?
  "True if `s` is a string that contains an unescaped CWL expression."
  [s]
  (and (string? s)
       (boolean (some map? (tokenize s)))))
