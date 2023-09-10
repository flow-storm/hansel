(ns hansel.instrument.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hansel.utils :as utils])
  (:import [java.io StringReader InputStreamReader]
           [clojure.lang LineNumberingPushbackReader]
           [clojure.lang RT]))

(declare macroexpand-all)

(defn get-all-ns-fn-clj [_] (map ns-name (all-ns)))

(defn get-all-ns-fn-cljs  [{:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)]
    (-> (compiler-env build-id) :cljs.analyzer/namespaces keys)))

(defn eval-in-ns-fn-clj [ns-symb form _]
  (binding [*ns* (find-ns ns-symb)]
    (eval form)))

(defn eval-in-ns-fn-cljs  [ns-symb form {:keys [build-id]}]
  (let [cljs-eval (requiring-resolve 'shadow.cljs.devtools.api/cljs-eval)
        {:keys [results err]} (cljs-eval build-id (pr-str form) {:ns ns-symb})]
    (if-not (str/blank? err)
      (throw (Exception. err))
      (try
        (read-string (first results))
        ;; HACKY: if we can't read the results, just return it as a string
        (catch Exception _ (first results))))))

(defn file-forms-fn-clj [ns-symb file-url _]
  (binding [*ns* (find-ns ns-symb)]
    (->> (LineNumberingPushbackReader. (StringReader. (format "[%s\n]" (slurp file-url))))
         (read {:read-cond :allow}))))

(defn file-forms-fn-cljs  [ns-symb file-url {:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        forms-seq (requiring-resolve 'cljs.analyzer.api/forms-seq)
        cenv (atom (compiler-env build-id))]
    (try
      #_:clj-kondo/ignore
      (utils/lazy-binding
       [cljs.analyzer/*cljs-ns* ns-symb
        cljs.env/*compiler* cenv]
       (let [file-str (slurp file-url)]
         (doall (forms-seq (StringReader. file-str) file-url))))
      (catch Exception e
        (binding [*out* *err*]
          (println "Error reading forms for " file-url)
          (.printStackTrace e))))))

(defn files-for-ns-fn-clj [ns-symb _]
  (let [ns-vars (vals (ns-interns (find-ns ns-symb)))
        r (->> ns-vars
               (keep (fn [v]
                       (let [file-name (:file (meta v))
                             file (when (and file-name
                                             (or
                                              (str/ends-with? file-name ".clj")
                                              (str/ends-with? file-name ".cljc")))
                                    (or (io/resource file-name)
                                        (.toURL (io/file file-name))))]
                         file)))
               (into #{}))]
    r))

(defn files-for-ns-fn-cljs  [ns-symb {:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        ns (-> (compiler-env build-id) :cljs.analyzer/namespaces ns-symb)
        file-name (or (-> ns :meta :file)
                      ;; this is to cover for a weird intermitent case
                      ;; where ns :meta :file is nil
                      (some-> ns :defs vals first :meta :file))
        file (when (and file-name
                        (or
                         (str/ends-with? file-name ".cljs")
                         (str/ends-with? file-name ".cljc")))
               (or (io/resource file-name)
                   (.toURL (io/file file-name))))]
    (when file
      [file])))

(defn source-fn-cljs [var-symb build-id]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        empty-env (requiring-resolve 'cljs.analyzer/empty-env)
        source-fn (requiring-resolve 'cljs.repl/source-fn)
        cenv (compiler-env build-id)
        aenv (assoc-in (empty-env) [:ns :name] 'cljs.user)]

    (utils/lazy-binding [cljs.analyzer/*cljs-ns* 'cljs.user
                         cljs.env/*compiler* (atom cenv)]
                        (source-fn aenv var-symb))))

;; TODO: write a macro that expand to all this compiler and analysis env wrap setup
(defn cljs-get-all-ns [build-id]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        all-ns (requiring-resolve 'cljs.analyzer.api/all-ns)
        cenv (compiler-env build-id)]
    (utils/lazy-binding [cljs.analyzer/*cljs-ns* 'cljs.user
                         cljs.env/*compiler* (atom cenv)]
                        (mapv str (all-ns)))))

(defn cljs-get-ns-interns [ns-symb build-id]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        ns-interns (requiring-resolve 'cljs.analyzer.api/ns-interns)
        cenv (compiler-env build-id)]
    (utils/lazy-binding [cljs.analyzer/*cljs-ns* 'cljs.user
                         cljs.env/*compiler* (atom cenv)]
                        (->> (ns-interns ns-symb)
                             keys
                             (mapv str)))))

(defn compiler-from-env [env]
  (if (contains? env :js-globals)
    :cljs
    :clj))

(defn strip-meta

  "Strip meta from form.
  If keys are provided, strip only those keys."

  ([form] (strip-meta form nil))
  ([form keys]
   (if (and (instance? clojure.lang.IObj form)
            (meta form))
     (if keys
       (with-meta form (apply dissoc (meta form) keys))
       (with-meta form nil))
     form)))

(defn deep-strip-meta [form ks]
  (utils/walk-code-form
   (fn [_ f]
     (if (or (symbol? f)
             (seq? f))
       (strip-meta f ks)
       f))
   form))

(defn listy?
  "Returns true if x is any kind of list except a vector."
  [x]
  (and (sequential? x) (not (vector? x))))

(defn- macroexpand+

  "A macroexpand version that support custom `macroexpand-1-fn`"

  [macroexpand-1-fn form]

  (let [ex (if (seq? form)
             (macroexpand-1-fn form)
             form)]
    (if (identical? ex form)
      form
      (macroexpand+ macroexpand-1-fn ex))))

(defn- specter-path-form? [expand-symbol form]
  (and (seq? form)
       (let [[x] form]
         (and
          (symbol? x)
          (= "path" (name x)) ;; this is here for perf reasons, so we don't expand on every symbol
          (#{'com.rpl.specter/path}  (expand-symbol x))))))

(defn- core-async-go-loop-form? [expand-symbol form]
  (and (seq? form)
       (let [[x] form]
         (and
          (symbol? x)
          (= "go-loop" (name x)) ;; this is here for perf reasons, so we don't expand on every symbol
          (#{'clojure.core.async/go-loop 'cljs.core.async/go-loop}  (expand-symbol x))))))

(defn- macroexpand-core-async-go [macroexpand-1-fn expand-symbol form original-key]
  `(clojure.core.async/go ~@(map #(macroexpand-all macroexpand-1-fn expand-symbol % original-key) (rest form))))

(defn walk-unquoted
  "Traverses form, an arbitrary data structure.  inner and outer are
  functions.  Applies inner to each element of form, building up a
  data structure of the same type, then applies outer to the result.
  Recognizes all Clojure data structures. Consumes seqs as with doall.

  Unlike clojure.walk/walk, does not traverse into quoted forms."
  [inner outer form]
  (if (and (listy? form) (= (first form) 'quote))
    (outer form)
    (walk/walk inner outer form)))

(defn core-async-go-form? [expand-symbol form]
  (and (seq? form)
       (let [[x] form]
         (and
          (symbol? x)
          (= "go" (name x))
          (#{'clojure.core.async/go 'cljs.core.async/go}  (expand-symbol x))))))

(defn parse-defn-expansion [defn-expanded-form]
  ;; (def my-fn (fn* ([])))
  (let [[_ var-name & fn-arities-bodies] defn-expanded-form]
    {:var-name var-name
     :fn-arities-bodies fn-arities-bodies}))

(defn macroexpand-all

  "Like `clojure.walk/macroexpand-all`, but preserves metadata.
  Also store the original form (unexpanded and stripped of
  metadata) in the metadata of the expanded form under original-key."

  [macroexpand-1-fn expand-symbol form & [original-key]]

  (cond

    ;; don't macroexpand com.rpl.specter/path since for weird macroexpansion and var resolving reasons
    ;; it fails if we try to evaluate the macroexpansion of path
    ;; Since we are skipping macroexpansion we also remove all coor tags so nothing tries to get
    ;; instrumented under this, which can't, since we can only instrument macroexpanded code
    (specter-path-form? expand-symbol form)
    (deep-strip-meta form [:hansel.instrument.forms/coor])

    (core-async-go-form? expand-symbol form)
    (macroexpand-core-async-go macroexpand-1-fn expand-symbol form original-key)

    (core-async-go-loop-form? expand-symbol form)
    (macroexpand-all macroexpand-1-fn expand-symbol (macroexpand-1 form) original-key)

    :else
    (let [md (meta form)
          expanded (walk-unquoted #(macroexpand-all macroexpand-1-fn expand-symbol % original-key)
                                  identity
                                  (if (and (seq? form)
                                           (not= (first form) 'quote))
                                    ;; Without this, `macroexpand-all`
                                    ;; throws if called on `defrecords`.
                                    (try (let [r (macroexpand+ macroexpand-1-fn form)]
                                           r)
                                         (catch ClassNotFoundException _ form))
                                    form))
          expanded-with-meta (utils/merge-meta

                              expanded

                              ;; also macroexpand meta, for forms like (deftest ...)
                              (when md (macroexpand-all macroexpand-1-fn expand-symbol md original-key))

                              (when original-key
                                ;; We have to quote this, or it will get evaluated by
                                ;; Clojure (even though it's inside meta).
                                {original-key (list 'quote (strip-meta form))}))]
      expanded-with-meta)))

(defn source-form
  [vsymb]
  (try
    (when-let [v (resolve vsymb)]
      (when-let [filepath (:file (meta v))]
        (let [strm (or (.getResourceAsStream (RT/baseLoader) filepath)
                       (io/input-stream filepath))
              var-ns (.-ns v)
              var-def-line (:line (meta v))]
          (when strm
            (with-open [rdr (LineNumberingPushbackReader. (InputStreamReader. strm))]
              (loop [prev-line (.getLineNumber rdr)]
                (let [form (binding [*ns* var-ns]
                             (read rdr))
                      new-line (.getLineNumber rdr)]
                  (if (<= prev-line var-def-line new-line)
                    form
                    (recur new-line)))))))))
    (catch Exception _ nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities to recognize forms in their macroexpanded forms ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expanded-lazy-seq-form?

  "Returns true if `form` is the expansion of (lazy-seq ...)"

  [form]

  (and (seq? form)
       (let [[a b] form]
         (and (= a 'new)
              (= b 'clojure.lang.LazySeq)))))

(defn expanded-defn-form? [form]
  (and (= (count form) 3)
       (= 'def (first form))
       (let [[_ _ x] form]
         (and (seq? x)
              (= (first x) 'fn*)))))

;; TODO: make all this simpler. I tried using pangloss/pattern everywhere
;; which is amazing but it doesn't work in ClojureScript, and I don't want
;; to bring it as a dep only for this functions.
(defn expanded-cljs-multi-arity-defn? [form]
  (when (seq? form)
    (let [[x1 & xs] form]
      (when (= x1 'do)
        (let [xset-fms (doall (keep (fn [x]
                                      (when (seq? x)
                                        (first x)))
                                    xs))]
          (when (seq? xset-fms)
            (let [[_ & xset] xset-fms]
              (and (expanded-defn-form? (first xs))
                   (pos? (count xset))
                   (every? #{'set! 'do}
                           (butlast xset))))))))))

(defn expanded-cljs-variadic-defn? [form]
  (when (seq? form)
    (let [[x1 x2 x3] form]
      (and (= x1 'do)
           (expanded-defn-form? x2)
           (seq? x3)
           (let [[_ xset1 xset2] x3]
             (and (seq? xset1) (seq? xset2)
                  (= (first xset1) 'set!)
                  (= (first xset2) 'set!)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utitlities to recognize ClojureScript forms in their original version ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- original-form-first-symb-name [form]
  (when (seq? form)
    (some-> form
            meta
            :hansel.instrument.forms/original-form
            rest
            ffirst
            name)))

(defn cljs-extend-type-form-types? [form _]
  (and (= "extend-type" (original-form-first-symb-name form))
       (every? (fn [[a0]]
                 (= 'set! a0))
               (rest form))))

(defn cljs-extend-type-form-basic? [form _]
  (and (= "extend-type" (original-form-first-symb-name form))
       (every? (fn [[a0]]
                 (= 'js* a0))
               (rest form))))
