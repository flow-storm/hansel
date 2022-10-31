(ns hansel.api
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.utils :as inst-utils]
            [hansel.instrument.runtime :as rt]))

(def instrument-form inst-forms/instrument)

(defmacro instrument [config form]
  (let [{:keys [init-forms inst-form]} (inst-forms/instrument (assoc config :env &env) form)]
    `(do ~@init-forms ~inst-form)))

(def clj-namespaces-config
  {:get-all-ns-fn inst-utils/get-all-ns-fn-clj
   :eval-in-ns-fn inst-utils/eval-in-ns-fn-clj
   :file-forms-fn inst-utils/file-forms-fn-clj
   :files-for-ns-fn inst-utils/files-for-ns-fn-clj
   :compiler :clj})

(def shadow-cljs-namespaces-config
  {:get-all-ns-fn inst-utils/get-all-ns-fn-cljs
   :eval-in-ns-fn inst-utils/eval-in-ns-fn-cljs
   :file-forms-fn inst-utils/file-forms-fn-cljs
   :files-for-ns-fn inst-utils/files-for-ns-fn-cljs
   :compiler :cljs})

(defn instrument-namespaces-clj [ns-prefixes config]
  (inst-ns/instrument-files-for-namespaces ns-prefixes
                                           (merge config
                                                  {:prefixes? true}
                                                  clj-namespaces-config)))

(defn instrument-namespaces-shadow-cljs [ns-prefixes config]
  (inst-ns/instrument-files-for-namespaces ns-prefixes
                                           (merge config
                                                  {:prefixes? true}
                                                  shadow-cljs-namespaces-config)))

(defmacro with-ctx [ctx & body]
  `(binding [rt/*runtime-ctx* ~ctx]
     ~@body))
