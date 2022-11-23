
![hansel](./docs/hansel.png)

Hansel leaving a trail o pebbles.

Hansel allows you to instrument Clojure[Script] forms and entire namespaces, so they leave a trail when they run.

It is ment as a platform to build tooling that depends on code instrumentation, like debuggers.

[![Clojars Project](https://img.shields.io/clojars/v/com.github.jpmonettas/hansel.svg)](https://clojars.org/com.github.jpmonettas/hansel)

## Clojure QuickStart

### Basic instrumentation

```clojure
(require '[hansel.api :as hansel]) ;; first require hansel api

;; Then define your "trace handlers"

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
  (println "[bind] data: " data))

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

;; [form-init] data: {:form-id -1653360108, :form (defn foo [a b] (+ a b)), :ns "dev", :def-kind :defn}
;; [fn-call] data: {:ns "dev", :fn-name "foo", :fn-args [2 3], :form-id -1653360108}
;; [bind] data: {:val 2, :coor nil, :symb a, :form-id -1653360108}
;; [bind] data: {:val 3, :coor nil, :symb b, :form-id -1653360108}
;; [expr-exec] data: {:coor [3 1], :result 2, :form-id -1653360108}
;; [expr-exec] data: {:coor [3 2], :result 3, :form-id -1653360108}
;; [expr-exec] data: {:coor [3], :result 5, :form-id -1653360108}
;; [fn-return] data: {:return 5, :form-id -1653360108}
```

### Conditional tracing

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

;; Your tracing handlers are going to be called every time and hansel.instrument.runtime/*runtime-ctx*
;; will contain :tracing-disabled? on each call

```

### Namespaces instrumentation

```clojure

(hansel/instrument-namespaces-clj #{"clojure.set"}
                                    '{:trace-form-init dev/print-form-init
                                      :trace-fn-call dev/print-fn-call
                                      :trace-fn-return dev/print-fn-return
                                      :trace-expr-exec dev/print-expr-exec
                                      :trace-bind dev/print-bind
									  :prefixes? false})
									  
(clojure.set/difference #{1 2 3} #{2})
```

### Vars and deep instrumentation

You can instrument any var by using `hansel/instrument-var-clj` like this :
```clojure
(hansel/instrument-var-clj
   'clojure.set/join
   '{:trace-form-init dev/print-form-init
     :trace-fn-call dev/print-fn-call
     :trace-fn-return dev/print-fn-return
     :trace-expr-exec dev/print-expr-exec
     :trace-bind dev/print-bind
     :deep? true}) ;; deep? is nil by default

;; it will return all the instrumented vars
;; => #{clojure.set/bubble-max-key
        clojure.set/index
        clojure.set/intersection
        clojure.set/join
        clojure.set/map-invert
        clojure.set/rename-keys}
```

## ClojureScript

### ClojureScript namespaces instrumentation (shadow-cljs only)

First start your shadow app :

```
rlwrap npx shadow-cljs browser-repl
```

On the ClojureScript side you need to define your handlers, so lets assume you have defined all your print-fns as before
inside cljs.user namespace.

On your shadow clj repl (you can connect to it via nRepl or by `npx shadow-cljs clj :browser-repl`) :
```

shadow.user> (require '[hansel.api :as hansel])

;; instrument your namespaces providing the handlers you defined in the ClojureScript runtime side

shadow.user> (hansel/instrument-namespaces-shadow-cljs
                #{"clojure.set"}
                '{:trace-form-init cljs.user/print-form-init
                  :trace-fn-call cljs.user/print-fn-call
                  :trace-fn-return cljs.user/print-fn-return
                  :trace-expr-exec cljs.user/print-expr-exec
                  :trace-bind cljs.user/print-bind
                  :build-id :browser-repl}) ;; <- need to provide your shadow app build-id
```

Now go back to your ClojureScript repl and run :

```
cljs.user> (clojure.set/difference #{1 2 3} #{2})
```

There is also `hansel.api/instrument-var-shadow-cljs` which works exactly like the Clojure version but 
the config also needs a `:build-id`.

## The coordinate system

All expression and bind traces data will contain a `:coor` field, which specifies the coordinate inside the form with `:form-id` this value refers to.

So the coordinate `[3 2]` in the form :

```clojure
(defn foo [a b] (+ a b))
```

refers to the `b` symbol in `(+ a b)` which is under coordinate `[3]`.

## Projects currently using Hansel

- [FlowStorm debugger](https://github.com/jpmonettas/flow-storm-debugger)
