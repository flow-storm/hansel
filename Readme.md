Instrument Clojure[Script] forms so they can be traced.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.jpmonettas/hansel.svg)](https://clojars.org/com.github.jpmonettas/hansel)

Example :

```clojure
(require '[hansel.api :as hansel]) ;; first require hansel api

;; Then define your "event handlers"

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

;; If you have any form as data you can instrument it with 
;; hansel.api/instrument-form, providing the tracing handlers you
;; are interested in. It will return the instrumented form

(hansel/instrument-form '{:trace-fn-call dev/print-fn-call
                          :trace-fn-return dev/print-fn-return}
                        '(defn foo [a b] (+ a b)))

;; If you want to evaluate the instrumented fn also you
;; have a convenience macro hansel.api/instrument also providing
;; your tracing handlers

(hansel/instrument {:trace-form-init dev/print-form-init
                    :trace-fn-call dev/print-fn-call
                    :trace-fn-return dev/print-fn-return
                    :trace-expr-exec dev/print-expr-exec
                    :trace-bind dev/print-bind}
                   (defn foo [a b] (+ a b)))

;; then you can call the function

(foo 2 3)

;; and the repl should print :

;; [form-init] data: {:form-id -1653360108, :form (defn foo [a b] (+ a b)), :ns "dev", :def-kind :defn}, ctx: null
;; [fn-call] data: {:ns "dev", :fn-name "foo", :fn-args [2 3], :form-id -1653360108}, ctx: null
;; [bind] data: {:val 2, :coor nil, :symb a, :form-id -1653360108}, ctx: null
;; [bind] data: {:val 3, :coor nil, :symb b, :form-id -1653360108}, ctx: null
;; [expr-exec] data: {:coor [3 1], :result 2, :form-id -1653360108}, ctx: null
;; [expr-exec] data: {:coor [3 2], :result 3, :form-id -1653360108}, ctx: null
;; [expr-exec] data: {:coor [3], :result 5, :form-id -1653360108}, ctx: null
;; [fn-return] data: {:return 5, :form-id -1653360108}, ctx: null
```

Conditional tracing :

```clojure
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

;; start with tracing disabled. It will be enabled/disabled depending on
;; the :trace/when meta
(hansel/with-ctx {:tracing-disabled? true}
  (factorial 5))

;; Your tracing handlers are going to be called every time but on your
;; ctx you will have :tracing-disabled? to check

```
