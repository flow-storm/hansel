(ns hansel.api
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.utils :as inst-utils]
            [hansel.utils :as utils]
            [hansel.instrument.runtime :as rt]
            [clojure.repl :as clj.repl]))

(defn instrument-form [config form]
  (inst-forms/instrument config form))

(defmacro instrument [config form]
  (let [{:keys [init-forms inst-form]} (inst-forms/instrument (assoc config :env &env) form)]
    `(do ~@init-forms ~inst-form)))

(defn instrument-var-clj [var-symb config]
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

(defn uninstrument-var-clj [var-symb]
  (instrument-var-clj var-symb {:uninstrument? true}))

(defn instrument-namespaces-clj [ns-prefixes config]
  (inst-ns/instrument-files-for-namespaces ns-prefixes
                                           (merge config
                                                  {:prefixes? true}
                                                  inst-ns/clj-namespaces-config)))

(defn uninstrument-namespaces-clj
  ([ns-prefixes] (uninstrument-namespaces-clj ns-prefixes {}))
  ([ns-prefixes config]
   (instrument-namespaces-clj ns-prefixes (assoc config :uninstrument? true))))

(defn instrument-var-shadow-cljs [var-symb {:keys [build-id] :as config}]
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

(defn uninstrument-var-shadow-cljs [var-symb config]
  (instrument-var-shadow-cljs var-symb (assoc config :uninstrument? true)))

(defn instrument-namespaces-shadow-cljs [ns-prefixes {:keys [build-id] :as config}]
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

(defn uninstrument-namespaces-shadow-cljs [ns-prefixes config]
  (instrument-namespaces-shadow-cljs ns-prefixes (assoc config :uninstrument? true)))

(defmacro with-ctx [ctx & body]
  `(binding [rt/*runtime-ctx* ~ctx]
     ~@body))
