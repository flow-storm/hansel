(ns hansel.instrument.forms

  "This namespace started as a fork of cider-nrepl instrument middleware but
  departed a lot from it to make it work for clojurescript and
  to make it able to trace more stuff.

  Provides utilities to recursively instrument forms for all our traces.

  If you are interested in understanding this, there is
  a nice flow diagram here : /docs/form_instrumentation.pdf "

  (:require
   [clojure.walk :as walk]
   [clojure.string :as str]
   [hansel.instrument.runtime :refer [*runtime-ctx*]]
   [hansel.utils :as utils]
   [clojure.core.async :as async]))


(declare instrument-outer-form)
(declare instrument-coll)
(declare instrument-special-form)
(declare instrument-function-call)
(declare instrument-cljs-extend-type-form-types)
(declare macroexpand-all)

;;;;;;;;;;;;;;;;;;;;
;; Some utilities ;;
;;;;;;;;;;;;;;;;;;;;

(defn- strip-meta

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

(defn- macroexpand+

  "A macroexpand version that support custom `macroexpand-1-fn`"

  [macroexpand-1-fn form]

  (let [ex (if (seq? form)
             (macroexpand-1-fn form)
             form)]
    (if (identical? ex form)
      form
      (macroexpand+ macroexpand-1-fn ex))))

(defn listy?
  "Returns true if x is any kind of list except a vector."
  [x]
  (and (sequential? x) (not (vector? x))))

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

(defn core-async-go-loop-form? [expand-symbol form]
  (and (seq? form)
       (let [[x] form]
         (and
          (symbol? x)
          (= "go-loop" (name x))
          (#{'clojure.core.async/go-loop 'cljs.core.async/go-loop}  (expand-symbol x))))))

(defn macroexpand-core-async-go [macroexpand-1-fn expand-symbol form original-key]
  `(clojure.core.async/go ~@(map #(macroexpand-all macroexpand-1-fn expand-symbol % original-key) (rest form))))

(defn macroexpand-all

  "Like `clojure.walk/macroexpand-all`, but preserves metadata.
  Also store the original form (unexpanded and stripped of
  metadata) in the metadata of the expanded form under original-key."

  [macroexpand-1-fn expand-symbol form & [original-key]]

  (cond
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
          expanded-with-meta (utils/merge-meta expanded
                               md
                               (when original-key
                                 ;; We have to quote this, or it will get evaluated by
                                 ;; Clojure (even though it's inside meta).
                                 {original-key (list 'quote (strip-meta form))}))]

      expanded-with-meta)))

(defn parse-defn-expansion [defn-expanded-form]
  ;; (def my-fn (fn* ([])))
  (let [[_ var-name & fn-arities-bodies] defn-expanded-form]
    {:var-name var-name
     :fn-arities-bodies fn-arities-bodies}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities to recognize forms in their macroexpanded forms ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- expanded-lazy-seq-form?

  "Returns true if `form` is the expansion of (lazy-seq ...)"

  [form]

  (and (seq? form)
       (let [[a b] form]
         (and (= a 'new)
              (= b 'clojure.lang.LazySeq)))))

(defn- expanded-defmethod-form? [form {:keys [compiler]}]
  (and (seq? form)
       (or (and (= compiler :clj)
                (= (count form) 5)
                (= (nth form 2) 'clojure.core/addMethod))
           (and (= compiler :cljs)
                (= (count form) 4)
                (= (first form) 'cljs.core/-add-method)))))

(defn- expanded-clojure-core-extend-form? [[symb] _]
  (= symb 'clojure.core/extend))

(defn- expanded-deftype-form [form _]
  (cond

    (and (> (count form) 5)
         (let [x (nth form 4)]
           (and (seq? x) (= (first x) 'deftype*))))
    :defrecord

    (and (seq? form)
         (>= (count form) 3)
         (let [x (nth form 2)]
           (and (seq? x) (= (first x) 'deftype*))))
    :deftype

    :else nil))

(defn- expanded-extend-protocol-form? [form _]
  (and (seq? form)
       (= 'do (first form))
       (seq? (second form))
       (= 'clojure.core/extend (-> form second first))))

(defn expanded-def-form? [form]
  (and (seq? form)
       (= (first form) 'def)))

(defn expanded-defn-form? [form]
  (and (= (count form) 3)
       (= 'def (first form))
       (let [[_ _ x] form]
         (and (seq? x)
              (= (first x) 'fn*)))))

(defn- expanded-cljs-multi-arity-defn? [[x1 & xs] _]
  (when (= x1 'do)
    (let [[_ & xset] (keep first xs)]
      (and (expanded-defn-form? (first xs))
           (pos? (count xset))
           (every? #{'set! 'do}
                   (butlast xset))))))

(defn- expanded-cljs-variadic-defn? [form]
  (when (seq? form)
    (let [[x1 x2 x3] form]
      (and (= x1 'do)
           (expanded-defn-form? x2)
           (seq? x3)
           (let [[_ xset1 xset2] x3]
             (and (seq? xset1) (seq? xset2)
                  (= (first xset1) 'set!)
                  (= (first xset2) 'set!)))))))

(defn expanded-form-type [form ctx]
  (when (seq? form)
    (cond

      (expanded-defn-form? form) :defn ;; this covers (defn foo [] ...), (def foo (fn [] ...)), and multy arities
      (expanded-defmethod-form? form ctx) :defmethod
      (or (expanded-clojure-core-extend-form? form ctx)
          (expanded-deftype-form form ctx)) :extend-type
      (expanded-extend-protocol-form? form ctx) :extend-protocol
      (expanded-def-form? form) :def)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utitlities to recognize ClojureScript forms in their original version ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- original-form-first-symb-name [form]
  (when (seq? form)
    (some-> form
            meta
            ::original-form
            rest
            ffirst
            name)))

(defn- cljs-extend-type-form-types? [form _]
  (and (= "extend-type" (original-form-first-symb-name form))
       (every? (fn [[a0]]
                 (= 'set! a0))
               (rest form))))

(defn- cljs-extend-type-form-basic? [form _]
  (and (= "extend-type" (original-form-first-symb-name form))
       (every? (fn [[a0]]
                 (= 'js* a0))
               (rest form))))

;;;;;;;;;;;;;;;;;;;;;
;; Instrumentation ;;
;;;;;;;;;;;;;;;;;;;;;

(def skip-instrument-forms
  "Set of special-forms that we don't want to wrap with instrumentation.
  These are either forms that don't do anything interesting (like
  `quote`) or forms that just can't be wrapped (like `catch` and
  `finally`)."
  ;; `recur` needs to be handled separately.
  '#{quote catch finally})

(defn- contains-recur?

  "Return true if form is not a `loop` or a `fn` and a `recur` is found in it."

  [form]
  (cond
    (seq? form) (case (first form)
                  recur true
                  loop* false
                  fn*   false
                  (some contains-recur? (rest form)))
    ;; `case` expands the non-default branches into a map.
    ;; We therefore expect a `recur` to appear in a map only
    ;; as the result of a `case` expansion.
    ;; This depends on Clojure implementation details.
    (map? form) (some contains-recur? (vals form))
    (vector? form) (some contains-recur? (seq form))
    :else false))

(defn- dont-instrument?

  "Return true if it's NOT ok to instrument `form`.
  Expressions we don't want to instrument are those listed in
  `skip-instrument-forms` and anything containing a `recur`
  form (unless it's inside a `loop`)."

  [[name :as form]]

  (or (skip-instrument-forms name)
      (contains-recur? form)))


(declare instrument-form-recursively)

(defn- instrument-coll

  "Instrument a collection."

  [coll ctx]

  (utils/merge-meta
      (walk/walk #(instrument-form-recursively %1 ctx) identity coll)
    (meta coll)))

(defn- uninteresting-symb?

  "Return true if it is a uninteresting simbol,
  like core.async generated symbols, the _ symbol, etc."

  [symb]

  (let [symb-name (name symb)]
    (or (= symb-name "_")
        (= symb-name "&")

        ;; symbols generated by clojure destructure
        (str/includes? symb-name "__")

        ;; core async generated symbols
        (str/includes? symb-name "state_")
        (str/includes? symb-name "statearr-")
        (str/includes? symb-name "inst_"))))

(defn- bind-tracer

  "Generates a form to trace a `symb` binding at `coor`."

  [symb coor {:keys [trace-bind] :as ctx}]

  (when-not (or (nil? trace-bind)
                (uninteresting-symb? symb))
    `(~trace-bind
      {:symb (quote ~symb)
       :val ~symb
       :coor ~coor
       :form-id ~(:form-id ctx)}
      *runtime-ctx*)))

(defn- args-bind-tracers

  "Generates a collection of forms to trace `args-vec` symbols at `coor`.
  Used for tracing all arguments of a fn."

  [args-vec ctx]

  ;; SPEED: maybe we can have a trace that send all bindings together, instead
  ;; of one binding trace per argument.

  ;; NOTE: Arguments bind trace coordinates are going to be always nil
  (->> args-vec
       (keep (fn [symb] (bind-tracer symb nil ctx)))))

(defn- expanded-fn-args-vec-symbols

  "Given a function `args` vector, return a vector of interesting symbols."

  [args]

  (into [] (remove #(#{'&} %) args)))

(defn- instrument-special-dot [args ctx]
  (list* (first args)
         ;; To handle the case when second argument to dot call
         ;; is a list e.g (. class-name (method-name args*))
         ;; The values present as args* should be instrumented.
         (let [s (second args)]
           (if (coll? s)
             (->> (instrument-coll (rest s) ctx)
                  (concat (cons (first s) '())))
             s))
         (instrument-coll (rest (rest args)) ctx)))

(defn- instrument-special-def [args ctx]
  (let [[sym & rargs] args
        is-fn-def? (and (seq? (first rargs))
                        (= 'fn* (-> rargs first first)))
        ctx (cond-> ctx
              is-fn-def? (assoc :fn-ctx {:trace-name sym
                                         :kind :defn}))]
    (list* sym (map (fn [arg] (instrument-form-recursively arg ctx)) rargs))))

(defn- instrument-special-loop*-like

  "Instrument lets and loops bindings right side recursively."

  [[name & args :as form] {:keys [disable] :as ctx}]

  (let [bindings (->> (first args)
                      (partition 2))
        inst-bindings-vec (if (or (disable :binding)
                                  (#{'loop* 'letfn*} name))
                            ;; don't mess with the bindings for loop* and letfn*
                            ;; letfn* doesn't make sense since all the bindings are fns and
                            ;; there is nothing to see there.
                            ;; If it is a loop* we are going to break the recur call
                            (first args)

                            ;; else it is a let* so we can add binding traces after each binding
                            (->> bindings
                                 (mapcat (fn [[symb x]]
                                           ;; like [a (+ 1 2)] will became
                                           ;; [a (+ 1 2)
                                           ;;  _ (bind-tracer a ...)]
                                           (-> [symb (instrument-form-recursively x ctx)]
                                               (into ['_ (bind-tracer symb (-> form meta ::coor) ctx)]))))
                                 vec))
        inst-body (if (and (= name 'loop*)
                           (not (disable :bindings)))
                    ;; if it is a loop* form we can still add bind traces to the body
                    (let [loop-binding-traces (->> bindings
                                                   (map (fn [[symb _]]
                                                          (bind-tracer symb (-> form meta ::coor) ctx))))]
                      `(~@loop-binding-traces
                        ~@(instrument-coll (rest args) ctx)))

                    ;; else just instrument the body recursively
                    (instrument-coll (rest args) ctx))]

    (cons inst-bindings-vec
          inst-body)))

(defn- instrument-extend-type-fn-arity-body [arity-body-forms outer-preamble form-ns fn-trace-name ctx]
  ;; All this nonsense is because inside type functions `this` behaves weirdly
  ;; This       : (fn* ([this] (do (println (js* "this"))))) prints the type object
  ;; while this : (fn* ([this] (+ 2 (do (println (js* "this")) 3)))) prints the window object on the browser
  ;;
  ;; Because of that, we can't instrument extend-type fn bodies normally and need to move the `this` binding
  ;; to the outside
  (let [[_ _ & this-inner-forms] (first arity-body-forms)
        inst-this-inner (instrument-outer-form ctx
                                               (instrument-coll this-inner-forms ctx)
                                               outer-preamble
                                               form-ns
                                               fn-trace-name)]
    `(let* [~'this (~'js* "this")]
       ~inst-this-inner)))

(defn- instrument-fn-arity-body

  "Instrument a (fn* ([] )) arity body. The core of functions body instrumentation."

  [[arity-args-vec & arity-body-forms :as arity] {:keys [compiler fn-ctx outer-form-kind form-id form-ns disable excluding-fns trace-fn-call] :as ctx}]
  (let [fn-trace-name (str (or (:trace-name fn-ctx) (gensym "fn-")))
        outer-preamble (if trace-fn-call
                         (-> []
                             (into [`(~trace-fn-call {:form-id ~form-id
                                                      :ns ~form-ns
                                                      :fn-name ~fn-trace-name
                                                      :fn-args ~(expanded-fn-args-vec-symbols arity-args-vec)}
                                      *runtime-ctx*)])
                             (into (args-bind-tracers arity-args-vec ctx)))
                         [])

        ctx' (-> ctx
                 (dissoc :fn-ctx)) ;; remove :fn-ctx so fn* down the road instrument as anonymous fns

        lazy-seq-fn? (expanded-lazy-seq-form? (first arity-body-forms))

        inst-arity-body-form (cond (or (and (disable :anonymous-fn) (= :anonymous (:kind fn-ctx))) ;; don't instrument anonymous-fn if they are disabled
                                       (and (#{:deftype :defrecord} outer-form-kind)
                                            (or (str/starts-with? fn-trace-name "->")
                                                (str/starts-with? fn-trace-name "map->"))) ;; don't instrument record constructors
                                       (excluding-fns (symbol form-ns fn-trace-name)))  ;; don't instrument if in excluding-fn

                               ;; skip instrumentation
                               `(do ~@arity-body-forms)

                               ;; on functions like (fn iter [x] (lazy-seq (cons x (iter x)))) skip outer
                               ;; instrumentation because it will make the process not lazy anymore and will
                               ;; risk a stackoverflow if the iteration is big
                               lazy-seq-fn?
                               (let [[a1 a2 fform] (first arity-body-forms)]
                                 `(~a1 ~a2 ~(instrument-form-recursively fform ctx')))

                               ;; we need to make an exception for extend-type body functions
                               ;; check the comments of `instrument-extend-type-fn-arity-body` for more details
                               (and (= :cljs compiler)
                                    (#{:extend-type} (:kind fn-ctx)))
                               (instrument-extend-type-fn-arity-body arity-body-forms outer-preamble form-ns fn-trace-name ctx')

                               ;; else instrument fn body
                               :else
                               (instrument-outer-form ctx'
                                                      (instrument-coll arity-body-forms ctx')
                                                      outer-preamble
                                                      form-ns
                                                      fn-trace-name))]
    (-> `(~arity-args-vec ~inst-arity-body-form)
        (utils/merge-meta (meta arity)))))

(defn- instrument-special-fn* [[_ & args] ctx]
  (let [[a1 & a1r] args
        [fn-name arities-bodies-seq] (cond

                                       ;; named fn like (fn* fn-name ([] ...) ([p1] ...))
                                       (symbol? a1)
                                       [a1 a1r]

                                       ;; anonymous fn like (fn* [] ...), comes from expanding #( % )
                                       (vector? a1)
                                       [nil [`(~a1 ~@a1r)]]

                                       ;; anonymous fn like (fn* ([] ...) ([p1] ...))
                                       :else
                                       [nil args])
        ctx (cond-> ctx
              (nil? (:fn-ctx ctx)) (assoc :fn-ctx {:trace-name fn-name
                                                   :kind :anonymous}))
        instrumented-arities-bodies (map #(instrument-fn-arity-body % ctx) arities-bodies-seq)]

    (if (nil? fn-name)
      `(~@instrumented-arities-bodies)
      `(~fn-name ~@instrumented-arities-bodies))))



(defn- instrument-special-case* [args ctx]
  (case (:compiler ctx)
    :clj (let [[a1 a2 a3 a4 a5 & ar] args
               inst-a5-map (->> a5
                                (map (fn [[k [v1 v2]]] [k [v1 (instrument-form-recursively v2 ctx)]]))
                                (into {}))]
           `(~a1 ~a2 ~a3 ~(instrument-form-recursively a4 ctx) ~inst-a5-map ~@ar))
    :cljs (let [[a1 left-vec right-vec else] args]
            `(~a1 ~left-vec ~(instrument-coll right-vec ctx) ~(instrument-form-recursively else ctx)))))

(defn- instrument-special-reify* [[proto-or-interface-vec & methods] ctx]
  (let [inst-methods (->> methods
                          (map (fn [[method-name args-vec & body]]
                                 (let [ctx (assoc ctx
                                                  :fn-ctx {:trace-name method-name
                                                           :kind :reify})
                                       [_ inst-body] (instrument-fn-arity-body `(~args-vec ~@body) ctx)]
                                   `(~method-name ~args-vec ~inst-body)))))]
    `(~proto-or-interface-vec ~@inst-methods)))

(defn- instrument-special-deftype*-clj [[a1 a2 a3 a4 a5 & methods] {:keys [outer-form-kind] :as ctx}]
  (let [inst-methods (->> methods
                          (map (fn [[method-name args-vec & body]]
                                 (if (and (= outer-form-kind :defrecord)
                                          (= "clojure.core" (namespace method-name)))

                                   ;; don't instrument defrecord types
                                   `(~method-name ~args-vec ~@body)

                                   (let [ctx (assoc ctx :fn-ctx {:trace-name method-name
                                                                 :kind :extend-type})
                                         [_ inst-body] (instrument-fn-arity-body `(~args-vec ~@body) ctx)]
                                     `(~method-name ~args-vec ~inst-body))))))]
    `(~a1 ~a2 ~a3 ~a4 ~a5 ~@inst-methods)))

(defn- instrument-special-js*-cljs [[js-form & js-form-args] ctx]
  `(~js-form ~@(instrument-coll js-form-args ctx)))

(defn- instrument-special-deftype*-cljs [[atype fields-vec x? extend-type-form] ctx]
  (let [inst-extend-type-form (instrument-cljs-extend-type-form-types extend-type-form ctx)]
    `(~atype ~fields-vec ~x? ~inst-extend-type-form)))

(defn- instrument-special-defrecord*-cljs [[arecord fields-vec rmap extend-type-form] ctx]
  (let [inst-extend-type-form (instrument-cljs-extend-type-form-types extend-type-form ctx)]
    (list arecord fields-vec rmap inst-extend-type-form)))

(defn- instrument-special-set! [args ctx]
  (list (first args)
        (instrument-form-recursively (second args) ctx)))

(defn- instrument-special-form

  "Instrument all Clojure and ClojureScript special forms. Dispatcher function."

  [[name & args :as form] {:keys [compiler] :as ctx}]
  (let [inst-args (try
                    (condp #(%1 %2) name
                      '#{do if recur throw finally try monitor-exit monitor-enter} (instrument-coll args ctx)
                      '#{new} (cons (first args) (instrument-coll (rest args) ctx))
                      '#{quote & var clojure.core/import*} args
                      '#{.} (instrument-special-dot args ctx)
                      '#{def} (instrument-special-def args ctx)
                      '#{set!} (instrument-special-set! args ctx)
                      '#{loop* let* letfn*} (instrument-special-loop*-like form ctx)
                      '#{deftype*} (case compiler
                                     :clj  (instrument-special-deftype*-clj args ctx)
                                     :cljs (instrument-special-deftype*-cljs args ctx))
                      '#{reify*} (instrument-special-reify* args ctx)
                      '#{fn*} (instrument-special-fn* form ctx)
                      '#{catch} `(~@(take 2 args)
                                  ~@(instrument-coll (drop 2 args) ctx))
                      '#{case*} (instrument-special-case* args ctx)

                      ;; ClojureScript special forms
                      '#{defrecord*} (instrument-special-defrecord*-cljs args ctx)
                      '#{js*} (instrument-special-js*-cljs args ctx))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "Failed to instrument" name args (pr-str form)
                                 ", please file a bug report: " e))
                      args))]
    (with-meta (cons name inst-args)
      (cond-> (meta form)

        ;; for clojure lets add meta to the fn* so when can
        ;; know if it has been instrumented.
        ;; It can be done for ClojureScript but first we need to fix
        ;; expanded-cljs-multi-arity-defn?
        ;; We are just skipping for cljs since the functionality that depends on this
        ;; flag (watch vars for inst/uninst) can't even be instrumented on ClojureScript
        (and (= name 'fn*) (= compiler :clj))
        (assoc :hansel/instrumented? true)))))


(defn- instrument-function-call

  "Instrument a regular function call sexp.
  This must be a sexp that starts with a symbol which is not a macro
  nor a special form.
  This includes regular function forms, like `(range 10)`, and also
  includes calls to Java methods, like `(System/currentTimeMillis)`."

  [[name & args :as form] ctx]

  (with-meta (cons name (instrument-coll args ctx)) (meta form)))

(defn- instrument-expression-form [form coor {:keys [form-id outer-form? trace-expr-exec trace-fn-return]}]
  (cond

    (and trace-fn-return outer-form?)
    `(~trace-fn-return {:return ~form
                        :form-id ~form-id}
      *runtime-ctx*) ;; trace-fn-return always evaluates to `form`

    trace-expr-exec
    `(~trace-expr-exec {:result ~form
                        :coor ~coor
                        :form-id ~form-id}
      *runtime-ctx*) ;; trace-expr-exec always evaluates to `form`

    :else
    form))

(defn- maybe-instrument

  "If the form has been tagged with ::coor on its meta, then instrument it
  with trace-and-return"

  ([form ctx]
   (let [{coor ::coor} (meta form)]

     (cond
       (and coor
            (not (and (seq? form) (= (first form) 'fn*)))) ;; skip wrapping instrumentation over (fn* ...)
       (instrument-expression-form form coor ctx)

       ;; If the form is a list and has no metadata, maybe it was
       ;; destroyed by a macro. Try guessing the extras by looking at
       ;; the first element. This fixes `->`, for instance.
       (seq? form)
       (let [{coor ::coor} (meta (first form))
             ;; coor (if (= (last extras) 0)
             ;;          (pop extras)
             ;;          extras)
             ]
         (if coor
           (instrument-expression-form form coor ctx)
           form))
       :else form))))


(defn- maybe-unwrap-outer-form-instrumentation [inst-form {:keys [trace-expr-exec]}]
  (if (and (seq? inst-form)
           (symbol? (first inst-form))
           (= trace-expr-exec (first inst-form)))

    ;; unwrap the trace-expr-exec
    (-> inst-form second :result)

    ;; else do nothing
    inst-form))

(defn- instrument-core-extend-form [[_ ext-type & exts] ctx]
  ;; We need special instrumentation for core/extend (extend-protocol and extend-type)
  ;; so we can trace fn-name, and trace that each fn is a protocol/type fn*
  (let [inst-ext (fn [[etype emap]]
                   (let [inst-emap (reduce-kv
                                    (fn [r k f]
                                      ;; HACKY: This ' is needed in `fn-name` because it will end up
                                      ;; in (fn* fn-name ([])) functions entry of extend-type after instrumenting
                                      ;; because of how fn-name thing is currently designed
                                      ;; This if we use the same name as the type key there it will compile
                                      ;; but will cause problems in situations when recursion is used
                                      ;; `fn-name` will be used only for reporting purposes, so there is no harm
                                      (let [fn-name (symbol (name k))]
                                        (assoc r k (instrument-form-recursively f
                                                                                (assoc ctx :fn-ctx {:trace-name fn-name
                                                                                                    :kind :extend-type})))))
                                    {}
                                    emap)]
                     (list etype inst-emap)))
        extensions (->> (partition 2 exts)
                        (mapcat inst-ext))]
    `(clojure.core/extend ~ext-type ~@extensions)))


(defn- instrument-cljs-multi-arity-defn [[_ xdef & xsets] ctx]
  (let [fn-name (second xdef)
        inst-sets-forms (keep (fn [[_ xarity fn-body]]
                           (when (and (seq? fn-body) (= (first fn-body) 'fn*))
                             (let [inst-bodies (instrument-form-recursively
                                                fn-body
                                                (assoc ctx :fn-ctx {:trace-name fn-name
                                                                    :kind :defn}))]
                               (list 'set! xarity inst-bodies))))
                              xsets)
        inst-code `(do ~xdef ~@inst-sets-forms)]
    inst-code))

(defn- instrument-cljs-extend-type-form-basic [[_ & js*-list] ctx]
  (let [inst-sets-forms (map (fn [[_ _ _ _ x :as js*-form]]
                               (let [fn-form? (and (seq? x) (= 'fn* (first x)))]
                                 (if fn-form?
                                   (let [[_ js-form fn-name type-str f-form] js*-form]
                                     (list 'js* js-form fn-name type-str (instrument-special-form
                                                                          f-form
                                                                          (assoc ctx :fn-ctx {:trace-name (name fn-name)
                                                                                              :kind :extend-type}))))
                                   js*-form)))
                             js*-list)
        inst-code `(do ~@inst-sets-forms)]
    inst-code))

(defn- instrument-cljs-extend-type-form-types [[_ & set!-list] ctx]

  (let [inst-sets-forms (map (fn [[_ _ x :as set!-form]]

                               (let [fn-form? (and (seq? x) (= 'fn* (first x)))]
                                 (if fn-form?
                                   (let [[_ set-field f-form] set!-form
                                         [_ _ fn-name] set-field]

                                     (if (str/starts-with? fn-name "-cljs$core")
                                       ;; don't instrument record types like ILookup, IKVReduce, etc
                                       set!-form

                                       ;; TODO: adjust fn-name here, fn-name at this stage is "-dev$Suber$sub$arity$1"
                                       (list 'set! set-field (instrument-special-form
                                                               f-form
                                                               (assoc ctx :fn-ctx {:trace-name (name fn-name)
                                                                                   :kind :extend-type})))))
                                   set!-form)))
                             set!-list)
        inst-code `(do ~@inst-sets-forms)]
    inst-code))

(defn- instrument-cljs-extend-protocol-form [[_ & extend-type-forms] ctx]
  (let [inst-extend-type-forms (map
                                (fn [ex-type-form]
                                  (let [instrument-cljs-extend-type-form (cond
                                                                           (cljs-extend-type-form-basic? ex-type-form ctx)
                                                                           instrument-cljs-extend-type-form-basic

                                                                           (cljs-extend-type-form-types? ex-type-form ctx)
                                                                           instrument-cljs-extend-type-form-types)]
                                    (instrument-cljs-extend-type-form ex-type-form ctx)))
                                    extend-type-forms)]
    `(do ~@inst-extend-type-forms)))

(defn- instrument-cljs-deftype-form [[_ deftype-form & xs] ctx]
  (let [inst-deftype-form (instrument-form-recursively deftype-form ctx)]
    `(do ~inst-deftype-form ~@xs)))

(defn- instrument-cljs-defrecord-form [[_ _ [_ defrecord-form] & x1s] ctx]
  (let [inst-defrecord-form (instrument-form-recursively defrecord-form ctx)]
    `(let* [] (do ~inst-defrecord-form) ~@x1s)))

(defn- instrument-defmethod-form [form {:keys [compiler] :as ctx}]
  (case compiler
    :clj (let [[_ mname _ mdisp-val mfn] form
               inst-mfn (instrument-form-recursively mfn ctx)]
           `(. ~mname clojure.core/addMethod ~mdisp-val ~inst-mfn))
    :cljs (let [[_ mname mdisp-val mfn] form
                inst-mfn (instrument-form-recursively mfn ctx)]
            `(cljs.core/-add-method ~mname ~mdisp-val ~inst-mfn))))

(defn special-symbol+?
  "Like clojure.core/special-symbol? but includes cljs specials"

  [symb]

  (or (special-symbol? symb)
      (#{'defrecord* 'js*} symb)))

(defn- instrument-core-async-go-block [form ctx]
  `(clojure.core.async/go
     ~@(map #(instrument-form-recursively % ctx) (rest form))))

(defn- instrument-function-like-form

  "Instrument form representing a function call or special-form."

  [[name :as form] ctx]
  (if-not (symbol? name)
    ;; If the car is not a symbol, nothing fancy is going on and we
    ;; can instrument everything.
    (maybe-instrument (instrument-coll form ctx) ctx)

    (cond

      (= name 'clojure.core.async/go)
      (instrument-core-async-go-block form ctx)

      ;; If special form, thread with care.
      (special-symbol+? name)
      (if (dont-instrument? form)

        ;; instrument down but don't wrap current one in instrumentation
        (instrument-special-form form ctx)

        ;; instrument down
        (maybe-instrument (instrument-special-form form ctx) ctx))

      ;; Otherwise, probably just a function. Just leave the
      ;; function name and instrument the args.
      :else
      (maybe-instrument (instrument-function-call form ctx) ctx))))

(defn- wrap-trace-when

  "Used for conditional tracing."

  [form enable-clause]

  `(binding [*runtime-ctx* (assoc *runtime-ctx* :tracing-disabled? (not ~enable-clause))]
     ~form))

(defn- instrument-form-recursively

  "Walk through form and return it instrumented with traces. "

  [form ctx]

  (let [inst-form (condp #(%1 %2) form
                    ;; Function call, macro call, or special form.
                    seq? (doall (instrument-function-like-form form ctx))
                    symbol? (maybe-instrument form ctx)
                    ;; Other coll types are safe, so we go inside them and only
                    ;; instrument what's interesting.
                    ;; Do we also need to check for seq?
                    coll? (doall (instrument-coll form ctx))
                    ;; Other things are uninteresting, literals or unreadable objects.
                    form)]

    ;; This is here since `instrument-form-recursively` it's the re-entry point.
    ;; When walking down we always check if the sub form meta contains a `:trace/when`,
    ;; it that is the case we wrap it appropiately
    (if-let [enable-clause (-> form meta :trace/when)]
      (wrap-trace-when inst-form enable-clause)
      inst-form)))

(defn- strip-instrumentation-meta

  "Remove all tags in order to reduce java bytecode size and enjoy cleaner code
  printouts."

  [form]
  (utils/walk-indexed
   (fn [_ f]
     (if (instance? clojure.lang.IObj f)

       (let [keys [::original-form ::coor]]
         (strip-meta f keys))

       f))
   form))

(defn- update-context-for-top-level-form

  "Set the context for instrumenting fn*s down the road."

  [{:keys [orig-outer-form] :as ctx} expanded-form qualified-first-symb]

  (cond-> ctx

    (#{'clojure.core/defmethod 'cljs.core/defmethod} qualified-first-symb)
    (assoc :fn-ctx {:trace-name (nth orig-outer-form 1)
                    :kind :defmethod
                    :dispatch-val (pr-str (nth orig-outer-form 2))}
           :outer-form-kind :defmethod)

    (#{'clojure.core/extend-protocol 'cljs.core/extend-protocol} qualified-first-symb)
    (assoc :outer-form-kind :extend-protocol)

    (#{'clojure.core/extend-type 'cljs.core/extend-type} qualified-first-symb)
    (assoc :outer-form-kind :extend-type)

    (#{'clojure.core/defrecord 'cljs.core/defrecord} qualified-first-symb)
    (assoc :outer-form-kind :defrecord)

    (#{'clojure.core/deftype 'cljs.core/deftype} qualified-first-symb)
    (assoc :outer-form-kind :deftype)

    (or (#{'clojure.core/defn 'cljs.core/defn} qualified-first-symb)
        (expanded-defn-form? expanded-form))
    (assoc :outer-form-kind :defn)

    (#{'clojure.core/def 'cljs.core/def} qualified-first-symb)
    (assoc :outer-form-kind :def)))

(defn- instrument-top-level-form

  "Like instrument-form-recursively but meant to be used around outer forms, not in recursions
  since it will do some checks that are only important in outer forms. "

  [expanded-form {:keys [form-id form-ns trace-form-init orig-outer-form expand-symbol compiler] :as ctx}]
  (let [qualified-first-symb (when (and (seq? orig-outer-form)
                                        (symbol? (first orig-outer-form)))
                               (expand-symbol (first orig-outer-form)))

        ctx (update-context-for-top-level-form ctx expanded-form qualified-first-symb)

        inst-form
        (cond

          ;; defmethod
          (#{'clojure.core/defmethod 'cljs.core/defmethod} qualified-first-symb)
          (instrument-defmethod-form expanded-form ctx)

          ;; extend-protocol
          (#{'clojure.core/extend-protocol 'cljs.core/extend-protocol} qualified-first-symb)
          (case compiler
            :clj `(do ~@(map (fn [ext-form] (instrument-core-extend-form ext-form ctx)) (rest expanded-form)))
            :cljs (instrument-cljs-extend-protocol-form expanded-form ctx))

          ;; extend-type
          (#{'clojure.core/extend-type 'cljs.core/extend-type} qualified-first-symb)
          (case compiler
            :clj (instrument-core-extend-form expanded-form ctx)
            :cljs (instrument-cljs-extend-type-form-types expanded-form ctx))

          ;; defrecord
          (#{'clojure.core/defrecord 'cljs.core/defrecord} qualified-first-symb)
          (case compiler
            :clj (instrument-form-recursively expanded-form ctx)
            :cljs (instrument-cljs-defrecord-form expanded-form ctx))

          ;; deftype
          (#{'clojure.core/deftype 'cljs.core/deftype} qualified-first-symb)
          (case compiler
            :clj (instrument-form-recursively expanded-form ctx)
            :cljs (instrument-cljs-deftype-form expanded-form ctx))

          ;; different kind of functions definitions, like (defn ...), (def ... (fn* [])), etc
          (and (= compiler :cljs) (expanded-cljs-variadic-defn? expanded-form))
          (do
            (println "Skipping variadic function definition since they aren't supported on ClojureScript yet." (:outer-orig-form ctx))
            expanded-form)

          (and (= compiler :cljs) (expanded-cljs-multi-arity-defn? expanded-form ctx))
          (instrument-cljs-multi-arity-defn expanded-form ctx)

          :else (instrument-form-recursively expanded-form ctx))
        inst-form-stripped (-> inst-form
                               (strip-instrumentation-meta)
                               (maybe-unwrap-outer-form-instrumentation ctx))]
    (if trace-form-init
      `(do
         (~trace-form-init ~(cond-> {:form-id form-id
                                     :form `'~orig-outer-form
                                     :ns form-ns
                                     :def-kind (:outer-form-kind ctx)}
                              (= :defmethod (:outer-form-kind ctx)) (assoc :dispatch-val (-> ctx :fn-ctx :dispatch-val)))
          ~*runtime-ctx*)
         ~inst-form-stripped)
      inst-form-stripped)))

(defn- instrument-outer-form
  "Add some special instrumentation that is needed only on the outer form."
  [ctx forms preamble _ _]
  `(do
     ~@(-> preamble
           (into [(instrument-expression-form (conj forms 'do) [] (assoc ctx :outer-form? true))]))))

(defn compiler-from-env [env]
  (if (contains? env :js-globals)
    :cljs
    :clj))

(defn- build-form-instrumentation-ctx [{:keys [disable excluding-fns tracing-disabled? trace-bind trace-form-init trace-fn-call trace-expr-exec trace-fn-return]} form-ns form env]
  (let [form-id (hash form)
        compiler (compiler-from-env env)
        [macroexpand-1-fn expand-symbol] (case compiler
                                           :cljs (let [cljs-macroexpand-1 (requiring-resolve 'cljs.analyzer/macroexpand-1)
                                                       cljs-resolve (requiring-resolve 'cljs.analyzer.api/resolve)]
                                                   [(partial cljs-macroexpand-1 env)
                                                    (fn [symb]
                                                      (or (:name (cljs-resolve env symb))
                                                          symb))])
                                           :clj  [macroexpand-1
                                                  (fn [symb]
                                                    (if-let [v (resolve symb)]
                                                      (symbol v)
                                                      symb))])]
    (assert (or (nil? disable) (set? disable)) ":disable configuration should be a set")
    {:environment      env
     :tracing-disabled? tracing-disabled?
     :compiler         compiler
     :orig-outer-form  form
     :form-id          form-id
     :form-ns          form-ns
     :excluding-fns     (or excluding-fns #{})
     :disable          (or disable #{}) ;; :expr :binding :anonymous-fn

     :trace-form-init trace-form-init
     :trace-fn-call trace-fn-call
     :trace-fn-return trace-fn-return
     :trace-expr-exec trace-expr-exec
     :trace-bind trace-bind

     :macroexpand-1-fn macroexpand-1-fn
     :expand-symbol expand-symbol}))

(defn instrument

  "Recursively instrument a form for tracing."

  [{:keys [env] :as config} form]

  (let [form-ns (or (:ns config) (str (ns-name *ns*)))
        {:keys [macroexpand-1-fn expand-symbol] :as ctx} (build-form-instrumentation-ctx config form-ns form env)
        tagged-form (utils/tag-form-recursively form ::coor)
        expanded-form (macroexpand-all macroexpand-1-fn expand-symbol tagged-form ::original-form)
        inst-code (instrument-top-level-form expanded-form ctx)]

    ;; Uncomment to debug
    ;; Printing on the *err* stream is important since
    ;; printing on standard output messes  with clojurescript macroexpansion
    #_(let [pprint-on-err (fn [msg x] (binding [*out* *err*] (println msg) (clojure.pprint/pprint x)))]
      (pprint-on-err "Expanmded form : " expanded-form)
      (pprint-on-err "Instrumented form : " inst-code))

    inst-code))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For working at the repl ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment



  )
