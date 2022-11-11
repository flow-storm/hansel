(ns dev
  (:require [hansel.instrument.runtime]))

(defn print-form-init [data]
  (println "[form-init] data:" data))

(defn print-fn-call [data]
  (println "[fn-call] data:" data))

(defn print-fn-return [{:keys [return] :as data}]
  (println "[fn-return] data:" data)
  return) ;; must return return!

(defn print-expr-exec [{:keys [result] :as data}]
  (println "[expr-exec] data:" data)
  result) ;; must return result!

(defn print-bind [data]
  (println "[bind] data:" data))
