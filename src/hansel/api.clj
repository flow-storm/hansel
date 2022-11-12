(ns hansel.api
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.utils :as inst-utils]
            [hansel.utils :as utils]
            [hansel.instrument.runtime :as rt]
            [clojure.repl :as clj.repl]))


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

(defn instrument-var-clj

  "Given a Clojure `var-symb` instrument it using `hansel.api/instrument-form`.
  Check that one for `config` options."

  [var-symb config]

  (let [ns-symb (symbol (namespace var-symb))
        form-ns (find-ns ns-symb)]
    (binding [*ns* form-ns]
      (let [form (some->> (clj.repl/source-fn var-symb)
                          (read-string {:read-cond :allow}))]
        (if form

          (let [v (find-var var-symb)
                vmeta (meta v)] ;; save the var meta
            (inst-ns/re-eval-form ns-symb form (merge inst-ns/clj-namespaces-config
                                                      config))

            ;; restore the var meta
            (alter-meta! v (constantly vmeta)))

          (println (format "Couldn't find source for %s" var-symb)))))))

(defn uninstrument-var-clj

  "Uninstrument a Clojure `var-symb` instrumented by `hansel.api/instrument-var-clj`"

  [var-symb]

  (instrument-var-clj var-symb {:uninstrument? true}))

(defn instrument-namespaces-clj

  "Instrument entire namespaces.
  `ns-prefixes` should be a set of ns name prefixes, like : #{\"cljs.\"}

  `config` should be a map optionally containing all the keys for `hansel.api/instrument-form`
  plus :
  - :verbose? true or false to indicate verbose logging
  - :excluding-ns a set of namepsaces names as string to be excluded from instrumentation
  - :excluding-fns a set of fully cualified fn symbols to be excluded from instrumentation"

  [ns-prefixes config]

  (inst-ns/instrument-files-for-namespaces ns-prefixes
                                           (merge config
                                                  {:prefixes? true}
                                                  inst-ns/clj-namespaces-config)))

(defn uninstrument-namespaces-clj

  "Uninstrument namespaces instrumented by `hansel.api/instrument-namespaces-clj`
  `ns-prefixes` should be a set  of namespaces prefixes as string."

  ([ns-prefixes] (uninstrument-namespaces-clj ns-prefixes {}))
  ([ns-prefixes config]
   (instrument-namespaces-clj ns-prefixes (assoc config
                                                 :uninstrument? true
                                                 :prefixes true))))

(defn instrument-var-shadow-cljs

  "Instrument a var for ClojureScript.
  The arguments are the same as `hansel.api/instrument-var-clj` plus
  the config should also contain a :build-id
  Meant to be used from shadow-cljs clojure repl (not the ClojureScript one)

  The :trace-* handlers should be fully qualified symbols of functions in the ClojureScript runtime."

  [var-symb {:keys [build-id] :as config}]

  (let [ns-symb (symbol (namespace var-symb))
        form (some->> (inst-utils/source-fn-cljs var-symb build-id)
                      (read-string {:read-cond :allow}))
        compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        empty-env (requiring-resolve 'cljs.analyzer/empty-env)
        cenv (compiler-env build-id)
        aenv (empty-env)]
    (if form
      #_:clj-kondo/ignore
      (utils/lazy-binding [cljs.env/*compiler* (atom cenv)]
                          (inst-ns/re-eval-form ns-symb form (merge inst-ns/shadow-cljs-namespaces-config
                                                                    config
                                                                    {:env aenv})))

      (println (format "Couldn't find source for %s" var-symb)))))

(defn uninstrument-var-shadow-cljs

  "Uninstrument vars instrumented by `hansel.api/instrument-var-shadow-cljs`"

  [var-symb config]
  (instrument-var-shadow-cljs var-symb (assoc config :uninstrument? true)))

(defn instrument-namespaces-shadow-cljs

  "Instrument entire namespaces for ClojureScript.
  The arguments are the same as `hansel.api/instrument-namespaces-clj` plus
  the config should also contain a :build-id
  Meant to be used from shadow-cljs clojure repl (not the ClojureScript one)

  The :trace-* handlers should be fully qualified symbols of functions in the ClojureScript runtime."

  [ns-prefixes {:keys [build-id] :as config}]

  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        empty-env (requiring-resolve 'cljs.analyzer/empty-env)
        cenv (compiler-env build-id)
        aenv (empty-env)]
    (utils/lazy-binding [cljs.env/*compiler* (atom cenv)]
                        (inst-ns/instrument-files-for-namespaces ns-prefixes
                                                                 (merge config
                                                                        {:prefixes? true
                                                                         :env aenv}
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
