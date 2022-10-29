(ns hansel.api
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.runtime :as rt]))

(def instrument-form inst-forms/instrument)

(defmacro instrument [config form]
  (inst-forms/instrument (assoc config :env &env) form))

(def instrument-namespaces inst-ns/instrument-files-for-namespaces)

(defmacro with-ctx [ctx & body]
  `(binding [rt/*runtime-ctx* ~ctx]
     ~@body))
