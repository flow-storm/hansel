(ns dev
  (:require [hansel.api :as hansel]
            [hansel.instrument.runtime :refer [*runtime-ctx*]]))

(defn print-form-init [data]
  (println "[form-init] data:" data ", ctx: " *runtime-ctx*))

(defn print-fn-call [data]
  (println "[fn-call] data:" data ", ctx: " *runtime-ctx*))

(defn print-fn-return [{:keys [return] :as data}]
  (println "[fn-return] data:" data ", ctx: " *runtime-ctx*)
  return) ;; must return return!

(defn print-expr-exec [{:keys [result] :as data}]
  (println "[expr-exec] data:" data ", ctx: " *runtime-ctx*)
  result) ;; must return result!

(defn print-bind [data]
  (println "[bind] data:" data ", ctx: " *runtime-ctx*))

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

  (hansel/instrument-namespaces-clj #{"clojure.set"}
                                    '{:trace-form-init dev/print-form-init
                                      :trace-fn-call dev/print-fn-call
                                      :trace-fn-return dev/print-fn-return
                                      :trace-expr-exec dev/print-expr-exec
                                      :trace-bind dev/print-bind
                                      :prefixes? false
                                      :uninstrument? true})


  ;; For trying in shadow clojure repl
  (require '[hansel.api :as hansel])
  (hansel/instrument-namespaces-shadow-cljs
   #{"clojure.set"}
   '{:trace-form-init cljs.user/print-form-init
     :trace-fn-call cljs.user/print-fn-call
     :trace-fn-return cljs.user/print-fn-return
     :trace-expr-exec cljs.user/print-expr-exec
     :trace-bind cljs.user/print-bind
     :uninstrument? false
     :build-id :browser-repl})

  (require '[clojure.set :as s])
  (clojure.set/difference #{1 2 3} #{2})
  (hansel/instrument-var-clj
   'clojure.set/difference
   '{:trace-form-init dev/print-form-init
     :trace-fn-call dev/print-fn-call
     :trace-fn-return dev/print-fn-return
     :trace-expr-exec dev/print-expr-exec
     :trace-bind dev/print-bind
     :uninstrument? false})
  )
