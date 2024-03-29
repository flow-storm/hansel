(ns hansel.instrument.forms-test
  (:require [clojure.string :as str]
            [hansel.instrument.runtime]
            [hansel.utils :as utils]            
            #?@(:clj [[hansel.instrument.test-utils :refer [def-instrumentation-test]]
                      [clojure.core.async :as async]
                      [clojure.core.match :refer [match]]]
                :cljs [[hansel.instrument.test-utils :refer [def-instrumentation-test] :include-macros true]
                       [clojure.test]
                       [cljs.core.match :refer-macros [match]]])))

;; just for clj-kondo
(comment match utils/format)
(declare function-definition-test)
(declare function-variadic-test)
(declare function-definition-test2)
(declare anonymous-fn-test)
(declare multi-arity-function-definition-test)
(declare defmethod-test)
(declare defrecord-test)
(declare deftype-test)
(declare extend-protocol-test)
(declare extend-type-test)
(declare core-async-go-block-test)
(declare core-async-go-loop-test)
(declare big-maps-and-sets-test)

(defn fn-str? [s]
  (and (string? s)
       (str/starts-with? s "fn-")))

;;;;;;;;;;;
;; Tests ;;
;;;;;;;;;;;
 
(def-instrumentation-test function-definition-test "Test defn instrumentation"
  
  :form (defn foo [a b] (+ a b))
  :run-form (foo 5 6)
  :should-return 11
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id -1653360108, :form #eq-guard '(defn foo [a b] (+ a b)), :ns "hansel.instrument.forms-test", :def-kind :defn}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "foo", :fn-args [5 6], :form-id -1653360108}]
            [:trace-bind {:val 5, :coor nil, :symb 'a, :form-id -1653360108}]
            [:trace-bind {:val 6, :coor nil, :symb 'b, :form-id -1653360108}]
            [:trace-expr-exec {:coor [3 1], :result 5, :form-id -1653360108}]
            [:trace-expr-exec {:coor [3 2], :result 6, :form-id -1653360108}]
            [:trace-expr-exec {:coor [3], :result 11, :form-id -1653360108}]
            [:trace-fn-return {:return 11, :form-id -1653360108}]])

;; Variadic functions instrumentation aren't supported on ClojureScript yet
#?(:clj
   (def-instrumentation-test function-variadic-test "Test variadic fn instrumentation"
     
     :form (defn foo1 [& args] (apply + args))
     :run-form (foo1 5 6)
     :should-return 11
     ;; :print-collected? true
     :tracing [[:trace-form-init {:form-id 1367613401, :form #eq-guard '(defn foo1 [& args] (apply + args)), :ns "hansel.instrument.forms-test", :def-kind :defn}]
               [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "foo1", :fn-args  #eq-guard '[(5 6)], :form-id 1367613401}]
               [:trace-bind {:val #eq-guard '(5 6) :coor nil, :symb 'args, :form-id 1367613401}]
               [:trace-expr-exec {:coor [3 1], :result _, :form-id 1367613401}]
               [:trace-expr-exec {:coor [3 2], :result #eq-guard '(5 6), :form-id 1367613401}]
               [:trace-expr-exec {:coor [3], :result 11, :form-id 1367613401}]
               [:trace-fn-return {:return 11, :form-id 1367613401}]]))

(def-instrumentation-test function-definition-test2 "Test def fn* instrumentation"
  
  :form (def foo2 (fn [a b] (+ a b)))
  :run-form (foo2 5 6)
  :should-return 11
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id -1100750367, :form #eq-guard '(def foo2 (fn [a b] (+ a b))), :ns "hansel.instrument.forms-test", :def-kind :defn}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "foo2", :fn-args [5 6], :form-id -1100750367}]
            [:trace-bind {:val 5, :coor nil, :symb 'a, :form-id -1100750367}]
            [:trace-bind {:val 6, :coor nil, :symb 'b, :form-id -1100750367}]
            [:trace-expr-exec {:coor [2 2 1], :result 5, :form-id -1100750367}]
            [:trace-expr-exec {:coor [2 2 2], :result 6, :form-id -1100750367}]
            [:trace-expr-exec {:coor [2 2], :result 11, :form-id -1100750367}]
            [:trace-fn-return {:return 11, :form-id -1100750367}]])

(def-instrumentation-test anonymous-fn-test "Test anonymous function instrumentation"

  :form (defn foo3 [xs]
          (->> xs (mapv (fn [i] (inc i)))))
  :run-form (foo3 [1 2 3])
  :should-return [2 3 4]
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id -143162624, :form #eq-guard '(defn foo3 [xs] (->> xs (mapv (fn [i] (inc i))))), :ns "hansel.instrument.forms-test", :def-kind :defn}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "foo3", :fn-args [[1 2 3]], :form-id -143162624}]
            [:trace-bind {:val [1 2 3], :coor nil, :symb 'xs, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 1], :result [1 2 3], :form-id -143162624}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name (_ :guard fn-str?), :fn-args [1], :form-id -143162624}]
            [:trace-bind {:val 1, :coor nil, :symb 'i, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2 1], :result 1, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2], :result 2, :form-id -143162624}]
            [:trace-fn-return {:return 2, :form-id -143162624}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name (_ :guard fn-str?), :fn-args [2], :form-id -143162624}]
            [:trace-bind {:val 2, :coor nil, :symb 'i, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2 1], :result 2, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2], :result 3, :form-id -143162624}]
            [:trace-fn-return {:return 3, :form-id -143162624}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name (_ :guard fn-str?), :fn-args [3], :form-id -143162624}]
            [:trace-bind {:val 3, :coor nil, :symb 'i, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2 1], :result 3, :form-id -143162624}]
            [:trace-expr-exec {:coor [3 2 1 2], :result 4, :form-id -143162624}]
            [:trace-fn-return {:return 4, :form-id -143162624}]
            [:trace-expr-exec {:coor [3], :result [2 3 4], :form-id -143162624}]
            [:trace-fn-return {:return [2 3 4], :form-id -143162624}]])

(def-instrumentation-test uncatched-fn-test "Test function unwinding tracing"

  :form (defn uncatched-throw []
          (let [f (fn []
                    (throw (ex-info "Dang" {})))]
            (f)))
  :run-form (try
              (uncatched-throw)
              #?(:clj (catch Exception _ :throwed)
                 :cljs (catch js/Error _ :throwed)))
  :should-return :throwed
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id 1143238905, :form #eq-guard '(defn uncatched-throw [] (let [f (fn [] (throw (ex-info "Dang" {})))] (f))), :ns "hansel.instrument.forms-test", :def-kind :defn, :file nil, :line nil}]
            [:trace-fn-call {:form-id 1143238905, :ns "hansel.instrument.forms-test", :fn-name "uncatched-throw", :unnamed-fn? false, :inner-fn? false, :fn-args []}]
            [:trace-bind {:val (_ :guard any?), :coor [3], :symb 'f, :form-id 1143238905}]
            [:trace-fn-call {:form-id 1143238905, :ns "hansel.instrument.forms-test", :fn-name (_ :guard fn-str?), :unnamed-fn? true, :inner-fn? true, :fn-args []}]
            [:trace-expr-exec {:coor [3 1 1 2 1], :result [:error "Dang"], :form-id 1143238905}]
            [:trace-fn-unwind {:form-id 1143238905, :throwable "Dang"}]
            [:trace-fn-unwind {:form-id 1143238905, :throwable "Dang"}]])



(def-instrumentation-test big-maps-and-sets-test "Test instrumentation of maps with more than 8 keys and sets"

  :form (defn big-maps-and-sets-fn []
          (assoc {(+ 42 42) 84
                  1 (+ 1 1)
                  2 (+ 2 2)
                  3 (+ 3 3)
                  4 (+ 4 4)
                  5 (+ 5 5)
                  6 (+ 6 6)
                  7 (+ 7 7)
                  8 (+ 8 8)
                  9 (+ 9 9)}
                 :x #{(+ 1 2) (+ 3 4) (+ 5 6) (+ 1 1 (* 2 2))}))
  :run-form (big-maps-and-sets-fn)
  :should-return {7 14, 1 2, 4 8, 6 12, 3 6, 2 4, 9 18, 5 10, :x #{7 6 3 11}, 8 16, 84 84}
  ;;:print-collected? true  
  :unsorted-tracing #{[:trace-form-init {:form-id 1936427945, :form '(defn big-maps-and-sets-fn [] (assoc {7 (+ 7 7), 1 (+ 1 1), 4 (+ 4 4), (+ 42 42) 84, 6 (+ 6 6), 3 (+ 3 3), 2 (+ 2 2), 9 (+ 9 9), 5 (+ 5 5), 8 (+ 8 8)} :x #{(+ 1 2) (+ 1 1 (* 2 2)) (+ 5 6) (+ 3 4)})), :ns "hansel.instrument.forms-test", :def-kind :defn, :file nil, :line nil}]
                      [:trace-fn-call {:form-id 1936427945, :ns "hansel.instrument.forms-test", :fn-name "big-maps-and-sets-fn", :unnamed-fn? false, :inner-fn? false, :fn-args []}]
                      [:trace-expr-exec {:coor [3 1 "V55"], :result 14, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V49"], :result 2, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V52"], :result 8, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V54"], :result 12, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V51"], :result 6, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V50"], :result 4, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V57"], :result 18, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V53"], :result 10, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "V56"], :result 16, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 1 "K844979548"], :result 84, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 3 "K875768657"], :result 7, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 3 "K1667464825" 3], :result 4, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 3 "K1667464825"], :result 6, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 3 "K842083153"], :result 3, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3 3 "K909454161"], :result 11, :form-id 1936427945}]
                      [:trace-expr-exec {:coor [3], :result {7 14, 1 2, 4 8, 6 12, 3 6, 2 4, 9 18, 5 10, :x #{7 6 3 11}, 8 16, 84 84}, :form-id 1936427945}]
                      [:trace-fn-return {:return {7 14, 1 2, 4 8, 6 12, 3 6, 2 4, 9 18, 5 10, :x #{7 6 3 11}, 8 16, 84 84}, :form-id 1936427945}]})

(def-instrumentation-test multi-arity-function-definition-test "Test multiarity function instrumentation"
  
  :form (defn bar
          ([a] (bar a 10))
          ([a b] (+ a b)))
  :run-form (bar 5)
  ;;:print-collected? true
  :should-return 15
  :tracing [[:trace-form-init {:form-id -1955739707, :form #eq-guard '(defn bar ([a] (bar a 10)) ([a b] (+ a b))), :ns "hansel.instrument.forms-test", :def-kind :defn}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "bar", :fn-args [5], :form-id -1955739707}]
            [:trace-bind {:val 5, :coor nil, :symb 'a, :form-id -1955739707}]
            [:trace-expr-exec {:coor [2 1 1], :result 5, :form-id -1955739707}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "bar", :fn-args [5 10], :form-id -1955739707}]
            [:trace-bind {:val 5, :coor nil, :symb 'a, :form-id -1955739707}]
            [:trace-bind {:val 10, :coor nil, :symb 'b, :form-id -1955739707}]
            [:trace-expr-exec {:coor [3 1 1], :result 5, :form-id -1955739707}]
            [:trace-expr-exec {:coor [3 1 2], :result 10, :form-id -1955739707}]
            [:trace-expr-exec {:coor [3 1], :result 15, :form-id -1955739707}]
            [:trace-fn-return {:return 15, :form-id -1955739707}]
            [:trace-expr-exec {:coor [2 1], :result 15, :form-id -1955739707}]
            [:trace-fn-return {:return 15, :form-id -1955739707}]])

(defmulti a-multi-method :type)

(def-instrumentation-test defmethod-test "Test defmethod instrumentation"

  :form (defmethod a-multi-method :some-type
          [m]
          (:x m))
  :run-form (a-multi-method {:type :some-type :x 42})
  :should-return 42
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id 1944849841, :form #eq-guard '(defmethod a-multi-method :some-type [m] (:x m)), :ns "hansel.instrument.forms-test", :def-kind :defmethod, :dispatch-val ":some-type"}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "a-multi-method", :fn-args [{:type :some-type, :x 42}], :form-id 1944849841}]
            [:trace-bind {:val {:type :some-type, :x 42}, :coor nil, :symb 'm, :form-id 1944849841}]
            [:trace-expr-exec {:coor [4 1], :result {:type :some-type, :x 42}, :form-id 1944849841}]
            [:trace-expr-exec {:coor [4], :result 42, :form-id 1944849841}]
            [:trace-fn-return {:return 42, :form-id 1944849841}]])

(defprotocol FooP
  (proto-fn-1 [x])
  (proto-fn-2 [x]))

(def-instrumentation-test defrecord-test "Test defrecord instrumentation"

  :form (defrecord ARecord [n]
          FooP
          (proto-fn-1 [_] (inc n))
          (proto-fn-2 [_] (dec n)))
  :run-form (+ (proto-fn-1 (->ARecord 5)) (proto-fn-2 (->ARecord 5)))
  :should-return 10
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id -1038078878, :form #eq-guard '(defrecord ARecord [n] FooP (proto-fn-1 [_] (inc n)) (proto-fn-2 [_] (dec n))), :ns "hansel.instrument.forms-test", :def-kind :defrecord}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-1" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1"), :fn-args #eq-guard [(->ARecord 5)], :form-id -1038078878}]
            [:trace-expr-exec {:coor [4 2 1], :result 5, :form-id -1038078878}]
            [:trace-expr-exec {:coor [4 2], :result 6, :form-id -1038078878}]
            [:trace-fn-return {:return 6, :form-id -1038078878}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-2" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1"), :fn-args #eq-guard [(->ARecord 5)], :form-id -1038078878}]
            [:trace-expr-exec {:coor [5 2 1], :result 5, :form-id -1038078878}]
            [:trace-expr-exec {:coor [5 2], :result 4, :form-id -1038078878}]
            [:trace-fn-return {:return 4, :form-id -1038078878}]])

(def-instrumentation-test deftype-test "Test deftype instrumentation"

  :form (deftype AType [n]
          FooP
          (proto-fn-1 [_] (inc n))
          (proto-fn-2 [_] (dec n)))
  :run-form (+ (proto-fn-1 (->AType 5)) (proto-fn-2 (->AType 5)))
  :should-return 10
  ;; :print-collected? true
  :tracing  [[:trace-form-init {:form-id 279996188, :form #eq-guard '(deftype AType [n] FooP (proto-fn-1 [_] (inc n)) (proto-fn-2 [_] (dec n))), :ns "hansel.instrument.forms-test", :def-kind :deftype}]
             [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-1" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1") :fn-args (_ :guard any?), :form-id 279996188}]
             [:trace-expr-exec {:coor [4 2 1], :result 5, :form-id 279996188}]
             [:trace-expr-exec {:coor [4 2], :result 6, :form-id 279996188}]
             [:trace-fn-return {:return 6, :form-id 279996188}]
             [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-2" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1"), :fn-args (_ :guard any?), :form-id 279996188}]
             [:trace-expr-exec {:coor [5 2 1], :result 5, :form-id 279996188}]
             [:trace-expr-exec {:coor [5 2], :result 4, :form-id 279996188}]
             [:trace-fn-return {:return 4, :form-id 279996188}]])


(defrecord BRecord [n])

(def-instrumentation-test extend-protocol-test "Test extend-protocol instrumentation"

  :form (extend-protocol FooP
          BRecord
          (proto-fn-1 [this] (inc (:n this)))
          (proto-fn-2 [this] (dec (:n this))))
  :run-form (+ (proto-fn-1 (->BRecord 5)) (proto-fn-2 (->BRecord 5)))
  :should-return 10
;;  :print-collected? true
  :tracing [[:trace-form-init {:form-id 969319502, :form #eq-guard '(extend-protocol FooP BRecord (proto-fn-1 [this] (inc (:n this))) (proto-fn-2 [this] (dec (:n this)))), :ns "hansel.instrument.forms-test", :def-kind :extend-protocol}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-1" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1"), :fn-args [#eq-guard (->BRecord 5)], :form-id 969319502}]
            [:trace-bind {:val #eq-guard (->BRecord 5), :coor nil, :symb 'this, :form-id 969319502}]
            [:trace-expr-exec {:coor [3 2 1 1], :result #eq-guard (->BRecord 5), :form-id 969319502}]
            [:trace-expr-exec {:coor [3 2 1], :result 5, :form-id 969319502}]
            [:trace-expr-exec {:coor [3 2], :result 6, :form-id 969319502}]
            [:trace-fn-return {:return 6, :form-id 969319502}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-2" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1"), :fn-args [#eq-guard (->BRecord 5)], :form-id 969319502}]
            [:trace-bind {:val #eq-guard (->BRecord 5), :coor nil, :symb 'this, :form-id 969319502}]
            [:trace-expr-exec {:coor [4 2 1 1], :result #eq-guard (->BRecord 5), :form-id 969319502}]
            [:trace-expr-exec {:coor [4 2 1], :result 5, :form-id 969319502}]
            [:trace-expr-exec {:coor [4 2], :result 4, :form-id 969319502}]
            [:trace-fn-return {:return 4, :form-id 969319502}]])

(defrecord CRecord [n])

(def-instrumentation-test extend-type-test "Test extend-type instrumentation"

  :form (extend-type CRecord            
          FooP
          (proto-fn-1 [this] (inc (:n this)))
          (proto-fn-2 [this] (dec (:n this))))
  :run-form (+ (proto-fn-1 (->CRecord 5)) (proto-fn-2 (->CRecord 5)))
  :should-return 10
  ;; :print-collected? true
  :tracing [[:trace-form-init {:form-id -1521217400, :form #eq-guard '(extend-type CRecord FooP (proto-fn-1 [this] (inc (:n this))) (proto-fn-2 [this] (dec (:n this)))), :ns "hansel.instrument.forms-test", :def-kind :extend-type}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-1" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1") :fn-args [#eq-guard (->CRecord 5)], :form-id -1521217400}]
            [:trace-bind {:val #eq-guard (->CRecord 5), :coor nil, :symb 'this, :form-id -1521217400}]
            [:trace-expr-exec {:coor [3 2 1 1], :result #eq-guard (->CRecord 5), :form-id -1521217400}]
            [:trace-expr-exec {:coor [3 2 1], :result 5, :form-id -1521217400}]
            [:trace-expr-exec {:coor [3 2], :result 6, :form-id -1521217400}]
            [:trace-fn-return {:return 6, :form-id -1521217400}]
            [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name #?(:clj "proto-fn-2" :cljs "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1"), :fn-args [#eq-guard (->CRecord 5)], :form-id -1521217400}]
            [:trace-bind {:val #eq-guard (->CRecord 5), :coor nil, :symb 'this, :form-id -1521217400}]
            [:trace-expr-exec {:coor [4 2 1 1], :result #eq-guard (->CRecord 5), :form-id -1521217400}]
            [:trace-expr-exec {:coor [4 2 1], :result 5, :form-id -1521217400}]
            [:trace-expr-exec {:coor [4 2], :result 4, :form-id -1521217400}]
            [:trace-fn-return {:return 4, :form-id -1521217400}]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Only testing clojure.core.async/go on Clojure since we can't block in ClojureScript and our testing system ;;
;; doesn't support async yet.                                                                                 ;;
;; We aren't doing anything special for ClojureScript go blocks, so testing on one should be enough           ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn async-chan? [x]
     (instance? clojure.core.async.impl.channels.ManyToManyChannel x)))

#?(:clj
   (def-instrumentation-test core-async-go-block-test "Test clojure.core.async/go instrumentation"

     :form (defn some-async-fn []
             (let [in-ch (async/chan)
                   out-ch (async/go
                            (loop []
                              (when-some [x (async/<! in-ch)]
                                x
                                (recur))))]
               
               [in-ch out-ch]))
     :run-form (let [[in-ch out-ch] (some-async-fn)]
                 (async/>!! in-ch "hello")
                 (async/>!! in-ch "bye")
                 (async/close! in-ch)
                 (async/<!! out-ch)
                 nil)
     :should-return nil
     ;;:print-collected? true         
     :tracing [[:trace-form-init {:form-id 1249185395, :form #eq-guard '(defn some-async-fn [] (let [in-ch (async/chan) out-ch (async/go (loop [] (when-some [x (async/<! in-ch)] x (recur))))] [in-ch out-ch])), :ns "hansel.instrument.forms-test", :def-kind :defn}]
               [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "some-async-fn", :fn-args [], :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 1], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-bind {:val (_ :guard async-chan?), :coor [3], :symb 'in-ch, :form-id 1249185395}]
               [:trace-bind {:val (_ :guard async-chan?), :coor [3], :symb 'out-ch, :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 2 0], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 2 1], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-expr-exec {:coor [3], :result [(_ :guard async-chan?) (_ :guard async-chan?)], :form-id 1249185395}]
               [:trace-fn-return {:return [(_ :guard async-chan?) (_ :guard async-chan?)], :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1 1], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1], :result "hello", :form-id 1249185395}]
               [:trace-bind {:val "hello", :coor nil, :symb 'x, :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 2], :result "hello", :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1 1], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1], :result "bye", :form-id 1249185395}]
               [:trace-bind {:val "bye", :coor nil, :symb 'x, :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 2], :result "bye", :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1 1], :result (_ :guard async-chan?), :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1 2 1 1], :result nil, :form-id 1249185395}]
               [:trace-expr-exec {:coor [3 1 3 1], :result nil, :form-id 1249185395}]]))


#?(:clj
   (def-instrumentation-test core-async-go-loop-test "Test clojure.core.async/go-loop instrumentation"

     :form (defn some-other-async-fn []
             (let [in-ch (async/chan)
                   out-ch (async/go-loop []
                            (when-some [x (async/<! in-ch)]
                              x
                              (recur)))]               
               [in-ch out-ch]))
     :run-form (let [[in-ch out-ch] (some-other-async-fn)]
                 (async/>!! in-ch "hello")
                 (async/>!! in-ch "bye")
                 (async/close! in-ch)
                 (async/<!! out-ch)
                 nil)
     :should-return nil
     ;; :print-collected? true
     :tracing [[:trace-form-init {:form-id 352274237, :form #eq-guard '(defn some-other-async-fn [] (let [in-ch (async/chan) out-ch (async/go-loop [] (when-some [x (async/<! in-ch)] x (recur)))] [in-ch out-ch])), :ns "hansel.instrument.forms-test", :def-kind :defn}]
               [:trace-fn-call {:ns "hansel.instrument.forms-test", :fn-name "some-other-async-fn", :fn-args [], :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 1], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-bind {:val (_ :guard async-chan?), :coor [3], :symb 'in-ch, :form-id 352274237}]
               [:trace-bind {:val (_ :guard async-chan?), :coor [3], :symb 'out-ch, :form-id 352274237}]
               [:trace-expr-exec {:coor [3 2 0], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-expr-exec {:coor [3 2 1], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-expr-exec {:coor [3], :result [(_ :guard async-chan?) (_ :guard async-chan?)], :form-id 352274237}]
               [:trace-fn-return {:return [(_ :guard async-chan?) (_ :guard async-chan?)], :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1 1], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1], :result "hello", :form-id 352274237}]
               [:trace-bind {:val "hello", :coor nil, :symb 'x, :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 2], :result "hello", :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1 1], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1], :result "bye", :form-id 352274237}]
               [:trace-bind {:val "bye", :coor nil, :symb 'x, :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 2], :result "bye", :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1 1], :result (_ :guard async-chan?), :form-id 352274237}]
               [:trace-expr-exec {:coor [3 1 3 2 1 1], :result nil, :form-id 352274237}]]))
