(ns dev
  (:require [hansel.instrument.runtime]))

(defn print-form-init [data ctx]
  (println "[form-init] data:" data ", ctx: " ctx))

(defn print-fn-call [data ctx]
  (println "[fn-call] data:" data ", ctx: " ctx))

(defn print-fn-return [{:keys [return] :as data} ctx]
  (println "[fn-return] data:" data ", ctx: " ctx)
  return) ;; must return return!

(defn print-expr-exec [{:keys [result] :as data} ctx]
  (println "[expr-exec] data:" data ", ctx: " ctx)
  result) ;; must return result!

(defn print-bind [data ctx]
  (println "[bind] data:" data ", ctx: " ctx))
