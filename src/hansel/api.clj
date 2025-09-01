(ns hansel.api
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.utils :as inst-utils]
            [hansel.utils :as utils :refer [log log-error]]
            [hansel.instrument.runtime :as rt]))


(defn instrument-form

  "Given any top level `form`, returns the form instrumented.
  `config` should be a map with optional [:trace-form-init
                                          :trace-fn-call
                                          :trace-fn-return
                                          :trace-expr-exec
                                          :trace-bind]"

  [config form]
  (inst-forms/instrument config form))

(defmacro instrument

  "A macro for instrumenting a `form` using `hansel.api/instrument-form`.
  Check that one for params docs."

  [config form]
  (let [{:keys [init-forms inst-form]} (inst-forms/instrument (assoc config :env &env) form)]
    `(do ~@init-forms ~inst-form)))

(defn- find-interesting-vars-references [resolve-fn-symb ns-symb form]
  (->> (tree-seq coll? seq form) ;; walk over code s-expressions
       (keep (fn [x]
               (when (symbol? x)
                 (when-let [vsymb (resolve-fn-symb ns-symb x)]
                   vsymb))))))

(defn instrument-var-clj

  "Given a Clojure `var-symb` instrument it using `hansel.api/instrument-form`.
  Config options are the same as `hansel.api/instrument-form` plus
  - :deep? When true recursively instrument all referenced vars (default to false)
  - :skip-namespaces A set of namespaces to skip when instrumenting deeply."

  ([var-symb config]
   (let [*instrumented-set (atom #{})]
     (instrument-var-clj var-symb config *instrumented-set)
     @*instrumented-set))

  ([var-symb {:keys [deep? uninstrument? skip-namespaces] :as config} *instrumented-set]
   (let [ns-symb (symbol (namespace var-symb))
         form-ns (find-ns ns-symb)]
     (log (format "Clojure re-evaluating var: %s deep?: %s instrument?: %s" var-symb deep? (not uninstrument?)))
     (binding [*ns* form-ns]
       (let [form (inst-utils/source-form var-symb)]
         (if form

           (let [v (find-var var-symb)
                 vmeta (meta v) ;; save the var meta
                 ;; use the namespace from the var meta instead of the one
                 ;; from the symbol.
                 ;; Some times they will not be the same, like in the case of using
                 ;; potemkin/import-vars
                 ns-symb (or (-> vmeta :ns ns-name) ns-symb)]
             (try

               (inst-ns/re-eval-form ns-symb form (merge inst-ns/clj-namespaces-config
                                                         config))
               (catch clojure.lang.ExceptionInfo ei (log-error (format "Error re-evaluating %s %s" var-symb (pr-str (ex-data ei))))))

             ;; restore the var meta
             (alter-meta! v (constantly vmeta))

             (swap! *instrumented-set conj var-symb)

             (when deep?
               (let [skip-namespaces (into #{"clojure.core"} skip-namespaces) ;; always skip clojure.core on deep instrumentation
                     resolve-fn-symb (fn resolve-fn-symb [ns-symb symb]
                                       (binding [*ns* (find-ns ns-symb)]
                                      (when-let [v (resolve symb)]
                                        (when (and (var? v) (fn? (deref v)))
                                          (symbol v)))))
                     sub-vars (cond->> (find-interesting-vars-references resolve-fn-symb ns-symb form)
                                (set? skip-namespaces) (remove (fn [vsymb] (skip-namespaces (namespace vsymb)))))]
                 (when (pos? (count sub-vars))
                   (doseq [sub-var-symb sub-vars]
                     (when-not (contains? @*instrumented-set sub-var-symb)
                       (instrument-var-clj sub-var-symb config *instrumented-set)))))))

           (throw (ex-info "Couldn't find source" {:var-symb var-symb}))))))))

(defn uninstrument-var-clj

  "Uninstrument a Clojure `var-symb` instrumented by `hansel.api/instrument-var-clj`"

  ([var-symb] (uninstrument-var-clj var-symb {}))
  ([var-symb config]
   (instrument-var-clj var-symb (assoc config :uninstrument? true))))

(defn instrument-namespaces-clj

  "Instrument entire namespaces.
  `namespaces-set` should be a set of ns name prefixes, like : #{\"cljs.\"}

  `config` should be a map optionally containing all the keys for `hansel.api/instrument-form`
  plus :
  - :verbose? true or false to indicate verbose logging
  - :excluding-ns a set of namepsaces names as string to be excluded from instrumentation
  - :excluding-fns a set of fully cualified fn symbols to be excluded from instrumentation
  - :prefixes? true or false indicate if `namespaces-set` namespaces should be treated as prefixes or not, defaults to true

  Returns a map with :
  - :inst-fns the set of instrumented fns
  - :affected-namespaces the set of affected namespaces
  "

  [namespaces-set config]

  (inst-ns/instrument-files-for-namespaces namespaces-set
                                           (merge {:prefixes? true}
                                                  config
                                                  inst-ns/clj-namespaces-config)))

(defn uninstrument-namespaces-clj

  "Uninstrument namespaces instrumented by `hansel.api/instrument-namespaces-clj`
  `ns-prefixes` should be a set  of namespaces prefixes as string."

  ([ns-prefixes] (uninstrument-namespaces-clj ns-prefixes {}))
  ([ns-prefixes config]
   (instrument-namespaces-clj ns-prefixes (assoc config
                                                 :uninstrument? true
                                                 :prefixes? true))))

(defn instrument-var-shadow-cljs

  "Instrument a var for ClojureScript.
  The arguments are the same as `hansel.api/instrument-var-clj` plus
  the config should also contain a :build-id
  Meant to be used from shadow-cljs clojure repl (not the ClojureScript one)

  The :trace-* handlers should be fully qualified symbols of functions in the ClojureScript runtime."

  ([var-symb config]
   (let [*instrumented-set (atom #{})]
     (instrument-var-shadow-cljs var-symb config *instrumented-set)
     @*instrumented-set))

  ([var-symb {:keys [build-id deep? uninstrument? skip-namespaces] :as config} *instrumented-set]

   (let [ns-symb (symbol (namespace var-symb))
         form (some->> (inst-utils/source-fn-cljs var-symb build-id)
                       (read-string {:read-cond :allow}))
         compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
         empty-env (requiring-resolve 'cljs.analyzer/empty-env)
         cenv (compiler-env build-id)
         aenv (empty-env)]
     (log (format "Shadow re-evaluating var: %s deep?: %s instrument?: %s" var-symb deep? (not uninstrument?)))
     (if form
       #_:clj-kondo/ignore
       (do
         (try
           (utils/lazy-binding [cljs.env/*compiler* (atom cenv)]
                               (inst-ns/re-eval-form ns-symb
                                                     form
                                                     (merge inst-ns/shadow-cljs-namespaces-config
                                                            config
                                                            {:env aenv})))
           (catch Exception e (log-error (format "Error re-evaluating %s" var-symb) e)))
         (swap! *instrumented-set conj var-symb)

         (when deep?
           (let [skip-namespaces (into #{"cljs.core"} skip-namespaces) ;; always skip cljs.core on deep instrumentation
                 resolve-fn-symb (fn resolve-fn-symb [ns-symb symb]
                                   (let [cljs-ns (-> (compiler-env build-id)
                                                     :cljs.analyzer/namespaces
                                                     (get ns-symb))]
                                     (if-let [symb-alias (namespace symb)]
                                       (let [ns-reqs (:requires cljs-ns)
                                             symb-ns (get ns-reqs (symbol symb-alias))]
                                         (when symb-ns
                                           (symbol (name symb-ns) (name symb))))

                                       ;; el if it doesn't have an alias lets check defs
                                       (let [ns-defs (:defs cljs-ns)
                                             protocol-symbol? (get-in ns-defs [symb :meta :protocol-symbol])
                                             type? (get-in ns-defs [symb :type])]
                                         (when (and (contains? ns-defs symb)
                                                    (not protocol-symbol?)
                                                    (not type?))
                                           (symbol (name ns-symb) (name symb)))))))
                 sub-vars (cond->> (find-interesting-vars-references resolve-fn-symb ns-symb form)
                            (set? skip-namespaces) (remove (fn [vsymb] (skip-namespaces (namespace vsymb)))))]
             (when (pos? (count sub-vars))
               (doseq [sub-var-symb sub-vars]
                 (when-not (contains? @*instrumented-set sub-var-symb)
                   (instrument-var-shadow-cljs sub-var-symb config *instrumented-set)))))))

       (throw (ex-info "Couldn't find source" {:var-symb var-symb}))))))

(defn uninstrument-var-shadow-cljs

  "Uninstrument vars instrumented by `hansel.api/instrument-var-shadow-cljs`"

  [var-symb config]
  (instrument-var-shadow-cljs var-symb (assoc config :uninstrument? true)))

(defn instrument-namespaces-shadow-cljs

  "Instrument entire namespaces for ClojureScript.
  The arguments are the same as `hansel.api/instrument-namespaces-clj` plus
  the config should also contain a :build-id
  Meant to be used from shadow-cljs clojure repl (not the ClojureScript one)

  The :trace-* handlers should be fully qualified symbols of functions in the ClojureScript runtime.

  Returns the set of instrumented fns."

  [namespaces-set {:keys [build-id] :as config}]

  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        empty-env (requiring-resolve 'cljs.analyzer/empty-env)
        cenv (compiler-env build-id)
        aenv (empty-env)]
    (utils/lazy-binding [cljs.env/*compiler* (atom cenv)]
                        (inst-ns/instrument-files-for-namespaces namespaces-set
                                                                 (merge {:prefixes? true
                                                                         :env aenv}
                                                                        config
                                                                        inst-ns/shadow-cljs-namespaces-config)))))

(defn uninstrument-namespaces-shadow-cljs

  "Uninstrument namespaces instrumented by `hansel.api/instrument-namespaces-shadow-cljs`"

  [ns-prefixes config]
  (instrument-namespaces-shadow-cljs ns-prefixes (assoc config :uninstrument? true)))

(defmacro with-ctx

  "Run `body` binding `hansel.instrument.runtime/*runtime-ctx*` to `ctx`"

  [ctx & body]

  `(binding [rt/*runtime-ctx* ~ctx]
     ~@body))
