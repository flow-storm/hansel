Instrument Clojure[Script] forms so they can be traced.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.jpmonettas/hansel.svg)](https://clojars.org/com.github.jpmonettas/hansel)

Example :

```clojure
(require '[hansel.instrument.forms :as inst-forms])

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

;; hansel.instrument.forms/instrument can be called with any form to instrument it

(inst-forms/instrument print-inst-config
                       '(defn foo [a b] (+ a b)))

;; you can wrap it on a macro and use it on your code

(defmacro i [form] (inst-forms/instrument print-inst-config form))

(i (defn foo [a b] (- a b)))

;; after running the i macro you should immediately see on printed on *out*

;; [form-init] data: {:form-id -1509256394, :ns "dev", :def-kind :defn}, rt-ctx: null, form: (defn foo [a b] (- a b)) 
;; [expr-exec] result: #'dev/foo, data: {:coor [], :form-id -1509256394}, rt-ctx: null

;; now if you call the function
(foo 2 3)

;; you should see also printed on out *out*

;; [fn-call] form-id: -1509256394, ns: dev, fn-name: foo, args-vec: [2 3], rt-ctx: null
;; [bind] symb: a, val: 2, data: {:coor nil, :form-id -1509256394}, rt-ctx: null
;; [bind] symb: b, val: 3, data: {:coor nil, :form-id -1509256394}, rt-ctx: null
;; [expr-exec] result: 2, data: {:coor [3 1], :form-id -1509256394}, rt-ctx: null
;; [expr-exec] result: 3, data: {:coor [3 2], :form-id -1509256394}, rt-ctx: null
;; [expr-exec] result: -1, data: {:coor [3], :form-id -1509256394}, rt-ctx: null
;; [expr-exec] result: -1, data: {:coor [], :form-id -1509256394, :outer-form? true}, rt-ctx: null
```

