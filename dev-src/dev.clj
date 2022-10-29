(ns dev
  (:require [hansel.api :as hansel]))

(defn print-form-init [data ctx]
  (println (format "[form-init] data: %s, ctx: %s" data ctx)))

(defn print-fn-call [data ctx]
  (println (format "[fn-call] data: %s, ctx: %s" data ctx)))

(defn print-fn-return [{:keys [return] :as data} ctx]
  (println (format "[fn-return] data: %s, ctx: %s" data ctx))
  return) ;; must return return!

(defn print-expr-exec [{:keys [result] :as data} ctx]
  (println (format "[expr-exec] data: %s, ctx: %s" data ctx))
  result) ;; must return result!

(defn print-bind [data ctx]
  (println (format "[bind] data: %s, ctx: %s" data ctx)))

(comment

  (hansel/instrument-form '{:trace-fn-call dev/print-fn-call
                            :trace-fn-return dev/print-fn-return}
                          '(defn foo [a b] (+ a b)))

  ;; becareful with the quoting
  (hansel/instrument {:trace-form-init dev/print-form-init
                      :trace-fn-call dev/print-fn-call
                      :trace-fn-return dev/print-fn-return
                      :trace-expr-exec dev/print-expr-exec
                      :trace-bind dev/print-bind}
                     (defn foo [a b] (+ a b)))

  (foo 2 3)

  (hansel/instrument {:trace-form-init dev/print-form-init
                      :trace-fn-call dev/print-fn-call
                      :trace-fn-return dev/print-fn-return
                      :trace-expr-exec dev/print-expr-exec
                      :trace-bind dev/print-bind}
                     (defn factorial [n]
                       (loop [i n
                              r 1]
                         (if (zero? i)
                           r
                           (recur ^{:trace/when (= i 2)} (dec i)
                                  (* r i))))))


  (hansel/with-ctx {:tracing-disabled? true}
    (factorial 5))
  )
