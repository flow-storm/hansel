(ns dev
  (:require [hansel.instrument.forms :as inst-forms]
            [hansel.instrument.namespaces :as inst-ns]
            [hansel.instrument.runtime :as rt]))


(defn print-form-init [data form rt-ctx]
  (println (format "[form-init] data: %s, rt-ctx: %s, form: %s "
                   data rt-ctx form)))

(defn print-fn-call [form-id ns fn-name args-vec rt-ctx]
  (println (format "[fn-call] form-id: %s, ns: %s, fn-name: %s, args-vec: %s, rt-ctx: %s"
                   form-id ns fn-name args-vec rt-ctx)))

(defn print-expr-exec [result data rt-ctx]
  (println (format "[expr-exec] result: %s, data: %s, rt-ctx: %s"
                   result data rt-ctx))
  result)

(defn print-bind [symb val data rt-ctx]
  (println (format "[bind] symb: %s, val: %s, data: %s, rt-ctx: %s"
                   symb val data rt-ctx)))

(def print-inst-config
  {:trace-form-init 'dev/print-form-init
   :trace-fn-call 'dev/print-fn-call
   :trace-expr-exec 'dev/print-expr-exec
   :trace-bind 'dev/print-bind})

(defmacro i [form] (inst-forms/instrument print-inst-config form))

(comment

  (inst-forms/instrument print-inst-config
                         '(defn foo [a b] (+ a b)))

  (i (defn foo [a b] (- a b)))

  (binding [rt/*runtime-ctx* {}]
    (foo 2 3))

  )
