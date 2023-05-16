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
   [hansel.instrument.utils :as inst-utils]))


(declare instrument-outer-form)
(declare instrument-coll)
(declare instrument-special-form)
(declare instrument-function-call)
(declare instrument-cljs-extend-type-form-types)
(declare macroexpand-all)

;;;;;;;;;;;;;;;;;;;;;
;; Instrumentation ;;
;;;;;;;;;;;;;;;;;;;;;

(defn make-instrumented-fn-tracker []
  (atom #{}))

(defn track-fn! [{:keys [instrumented-fns]} fn-ns fn-name arity]
  (let [fn-fq-symb (symbol fn-ns fn-name)]
    (swap! instrumented-fns conj [fn-fq-symb arity])))

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
    (let [symb (with-meta symb {})]
      `(~trace-bind
        {:symb (quote ~symb)
         :val ~symb
         :coor ~coor
         :form-id ~(:form-id ctx)}))))

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

  (->> args
       (remove #(#{'&} %))
       (map #(with-meta % {}))
       (into [])))

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
    (list* (utils/merge-meta sym (instrument-form-recursively (meta sym) ctx)) ;; instrument sym meta, is where :test are stored
           (map (fn [arg] (instrument-form-recursively arg ctx)) rargs))))

(defn- instrument-special-loop*-like

  "Instrument lets and loops bindings right side recursively."

  [[name & args :as form] ctx]

  (let [bindings (->> (first args)
                      (partition 2))
        inst-bindings-vec (if (#{'loop* 'letfn*} name)
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
        inst-body (if (= name 'loop*)
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
  (if (:extending-basic-type? ctx)
    ;; Extending a basic type (number, string, ...)
    (instrument-outer-form ctx
                           (instrument-coll arity-body-forms ctx)
                           outer-preamble
                           form-ns
                           fn-trace-name)

    ;; Else we are extending a user defined type
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
         ~inst-this-inner))))

(defn- instrument-fn-arity-body

  "Instrument a (fn* ([] )) arity body. The core of functions body instrumentation."

  [[arity-args-vec & arity-body-forms :as arity] {:keys [compiler fn-ctx outer-form-kind form-id form-ns disable excluding-fns trace-fn-call] :as ctx}]
  (let [unnamed-fn? (nil? (:trace-name fn-ctx))
        inner-fn? (= :anonymous (:kind fn-ctx))
        fn-trace-name (str (or (:trace-name fn-ctx) (gensym "fn-")))
        fn-args (expanded-fn-args-vec-symbols arity-args-vec)
        outer-preamble (if trace-fn-call
                         (-> []
                             (into [`(~trace-fn-call ~{:form-id form-id
                                                       :ns form-ns
                                                       :fn-name fn-trace-name
                                                       :unnamed-fn? unnamed-fn?
                                                       :inner-fn? inner-fn?
                                                       :fn-args fn-args})])
                             (into (args-bind-tracers arity-args-vec ctx)))
                         [])

        _ (when-not unnamed-fn?
            (track-fn! ctx form-ns fn-trace-name (count fn-args)))

        ctx' (-> ctx
                 (dissoc :fn-ctx)) ;; remove :fn-ctx so fn* down the road instrument as anonymous fns

        lazy-seq-fn? (inst-utils/expanded-lazy-seq-form? (first arity-body-forms))

        inst-arity-body-form (cond (or (and (disable :anonymous-fn) inner-fn?) ;; don't instrument anonymous-fn if they are disabled
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
        instrumented-arities-bodies (mapv #(instrument-fn-arity-body % ctx) arities-bodies-seq)]

    (if (nil? fn-name)
      `(~@instrumented-arities-bodies)
      `(~fn-name ~@instrumented-arities-bodies))))



(defn- instrument-special-case* [args ctx]
  (case (:compiler ctx)
    :clj (let [[a1 a2 a3 a4 a5 & ar] args
               inst-a5-map (->> a5
                                (map (fn [[k [v1 v2]]] [k [v1 (instrument-form-recursively v2 ctx)]]))
                                (into (sorted-map)))]
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
                        :form-id ~form-id}) ;; trace-fn-return always evaluates to `form`

    trace-expr-exec
    `(~trace-expr-exec {:result ~form
                        :coor ~coor
                        :form-id ~form-id}) ;; trace-expr-exec always evaluates to `form`

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

(defn- instrument-cljs-extend-type-form-basic

  "Instrument extend-types over basic primitive types (number, string, ...)"

  [[_ & js*-list] ctx]
  (let [inst-sets-forms (mapv (fn [[_ _ _ _ x :as js*-form]]
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

(defn- instrument-cljs-extend-type-form-types

  "Instrument extend-types over user defined types (types defined with deftype, defrecord, etc)"

  [[_ & set!-list] ctx]

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
  (let [inst-extend-type-forms (mapv
                                (fn [ex-type-form]
                                  (let [[instrument-cljs-extend-type-form ctx']
                                        (cond
                                          (inst-utils/cljs-extend-type-form-basic? ex-type-form ctx)
                                          [instrument-cljs-extend-type-form-basic (assoc ctx :extending-basic-type? true)]

                                          (inst-utils/cljs-extend-type-form-types? ex-type-form ctx)
                                          [instrument-cljs-extend-type-form-types (assoc ctx :extending-basic-type? false)])]

                                    ;; extend-protocol just expand to (do (extend-type ...) (extend-type ...) ...)
                                    ;; In ClojureScript extend-type macroexpand to two different things depending
                                    ;; if the type in question is a primitive (string, number, ...) or a defined one (with defrecord, etc)
                                    (instrument-cljs-extend-type-form ex-type-form ctx')))
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

(defn- instrument-core-async-go-block [form {:keys [compiler] :as ctx}]
  (let [go-symb (case compiler
                  :clj  'clojure.core.async/go
                  :cljs 'cljs.core.async/go)]
    `(~go-symb
      ~@(map #(instrument-form-recursively % ctx) (rest form)))))

(defn- instrument-function-like-form

  "Instrument form representing a function call or special-form."

  [[name :as form] ctx]
  (if-not (symbol? name)
    ;; If the car is not a symbol, nothing fancy is going on and we
    ;; can instrument everything.
    (maybe-instrument (instrument-coll form ctx) ctx)

    (cond

      (#{'clojure.core.async/go 'cljs.core.async/go} name)
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
         (inst-utils/strip-meta f keys))

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
        (inst-utils/expanded-defn-form? expanded-form))
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
          (and (= compiler :cljs) (inst-utils/expanded-cljs-variadic-defn? expanded-form))
          (do
            (println "Skipping variadic function definition since they aren't supported on ClojureScript yet." (:outer-orig-form ctx))
            expanded-form)

          (and (= compiler :cljs) (inst-utils/expanded-cljs-multi-arity-defn? expanded-form ctx))
          (instrument-cljs-multi-arity-defn expanded-form ctx)

          :else (instrument-form-recursively expanded-form ctx))
        inst-form-stripped (-> inst-form
                               (strip-instrumentation-meta)
                               (maybe-unwrap-outer-form-instrumentation ctx))]
    (cond-> {:inst-form inst-form-stripped}
      trace-form-init (assoc :init-forms
                             [`(~trace-form-init ~(cond-> {:form-id form-id
                                                           :form `'~orig-outer-form
                                                           :ns form-ns
                                                           :def-kind (:outer-form-kind ctx)}
                                                    (= :defmethod (:outer-form-kind ctx)) (assoc :dispatch-val (-> ctx :fn-ctx :dispatch-val))))]))))

(defn- instrument-outer-form
  "Add some special instrumentation that is needed only on the outer form."
  [ctx forms preamble _ _]
  `(do
     ~@(-> preamble
           (into [(instrument-expression-form (conj forms 'do) [] (assoc ctx :outer-form? true))]))))

(defn- build-form-instrumentation-ctx [{:keys [disable excluding-fns tracing-disabled? trace-bind
                                               trace-form-init trace-fn-call trace-expr-exec trace-fn-return]} form-ns form env]
  (let [form-id (hash form)
        compiler (inst-utils/compiler-from-env env)
        [macroexpand-1-fn expand-symbol] (case compiler
                                           :cljs (let [cljs-macroexpand-1 (requiring-resolve 'cljs.analyzer/macroexpand-1)
                                                       cljs-resolve (requiring-resolve 'cljs.analyzer.api/resolve)]
                                                   [(fn [form]
                                                      (cljs-macroexpand-1 env form))
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

     :instrumented-fns (make-instrumented-fn-tracker)
     :macroexpand-1-fn macroexpand-1-fn
     :expand-symbol expand-symbol}))

(defn- normalize-gensyms

  "When the reader reads things like #(+ % %) it uses a global id to generate symbols,
  so everytime will read something different, like :

  (fn* [p1__37935#] (+ p1__37935# p1__37935#))
  (fn* [p1__37939#] (+ p1__37939# p1__37939#))

  Normalize symbol can be applied to generate things like :

  (fn* [p__0] (+ p__0 p__0)).

  Useful for generating stable form hashes."

  [form]
  (let [psym->id (atom {})
        gensym? (fn [x]
                  (and (symbol? x)
                       (re-matches #"^p([\d])__([\d]+)#$" (name x))))
        normal (fn [psym]
                 (let [ids @psym->id
                       nsymid (if-let [id (get ids psym)]
                                id

                                (if (empty? ids)
                                  0
                                  (inc (apply max (vals ids)))))]

                   (swap! psym->id assoc psym nsymid)

                   (symbol (str "p__" nsymid))))]
    (walk/postwalk
     (fn [x]
       (if (gensym? x)
         (normal x)
         x))
     form)))

(defn instrument

  "Instrument a form for tracing.
  Returns a map with :inst-form and :init-forms."

  [{:keys [env normalize-gensyms?] :as config} form]
  (let [form (if normalize-gensyms?
               (normalize-gensyms form)
               form)
        form-ns (or (:ns config) (str (ns-name *ns*)))
        {:keys [macroexpand-1-fn expand-symbol] :as ctx} (build-form-instrumentation-ctx config form-ns form env)
        tagged-form (utils/tag-form-recursively form ::coor)
        expanded-form (inst-utils/macroexpand-all macroexpand-1-fn expand-symbol tagged-form ::original-form)
        inst-result (instrument-top-level-form expanded-form ctx)]

    ;; Uncomment to debug
    ;; Printing on the *err* stream is important since
    ;; printing on standard output messes  with clojurescript macroexpansion
    #_(let [pprint-on-err (fn [msg x] (binding [*out* *err*] (println msg) (clojure.pprint/pprint x)))]
        (pprint-on-err "Input form : " form)
        (pprint-on-err "Expanded form : " expanded-form)
        (pprint-on-err "Instrumented form : " inst-result))

    (assoc inst-result
           :instrumented-fns (-> ctx :instrumented-fns deref))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For working at the repl ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment



  )
