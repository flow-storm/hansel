(ns hansel.instrument.forms-test
  (:require [clojure.string :as str]
            [hansel.instrument.runtime]
            #?@(:clj [[hansel.instrument.test-utils :refer [def-instrumentation-test]]
                      [clojure.core.async :as async]]
                :cljs [[hansel.instrument.test-utils :refer [def-instrumentation-test] :include-macros true]
                       [clojure.test]])))

;; just for clj-kondo
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
  :print-collected? true
  :tracing '[[:trace-form-init {:def-kind :defn, :ns "hansel.instrument.forms-test", :form-id -1653360108} (defn foo [a b] (+ a b)) nil]
             [:trace-fn-call -1653360108 "hansel.instrument.forms-test" "foo" [5 6] nil]
             [:trace-bind a 5 {:coor nil, :form-id -1653360108} nil]
             [:trace-bind b 6 {:coor nil, :form-id -1653360108} nil]
             [:trace-expr-exec 5 {:coor [3 1], :form-id -1653360108} nil]
             [:trace-expr-exec 6 {:coor [3 2], :form-id -1653360108} nil]
             [:trace-expr-exec 11 {:coor [3], :form-id -1653360108} nil]
             [:trace-expr-exec 11 {:coor [], :form-id -1653360108, :outer-form? true} nil]])

;; Variadic functions instrumentation aren't supported on ClojureScript yet
#?(:clj
   (def-instrumentation-test function-variadic-test "Test variadic fn instrumentation"
     
     :form (defn foo1 [& args] (apply + args))
     :run-form (foo1 5 6)
     :should-return 11
     ;; :print-collected? true
     :tracing [[:trace-form-init {:def-kind :defn, :ns "hansel.instrument.forms-test", :form-id 1367613401} '(defn foo1 [& args] (apply + args)) nil]
               [:trace-fn-call 1367613401 "hansel.instrument.forms-test" "foo1" ['(5 6)] nil]
               [:trace-bind 'args '(5 6) {:coor nil, :form-id 1367613401} nil]
               [:trace-expr-exec any? {:coor [3 1], :form-id 1367613401} nil]
               [:trace-expr-exec '(5 6) {:coor [3 2], :form-id 1367613401} nil]
               [:trace-expr-exec 11 {:coor [3], :form-id 1367613401} nil]
               [:trace-expr-exec 11 {:coor [], :form-id 1367613401, :outer-form? true} nil]]))

(def-instrumentation-test function-definition-test2 "Test def fn* instrumentation"
  
  :form (def foo2 (fn [a b] (+ a b)))
  :run-form (foo2 5 6)
  :should-return 11
  ;; :print-collected? true
  :tracing '[[:trace-form-init {:def-kind :defn, :ns "hansel.instrument.forms-test", :form-id -1100750367} (def foo2 (fn [a b] (+ a b))) nil]
             [:trace-fn-call -1100750367 "hansel.instrument.forms-test" "foo2" [5 6] nil]
             [:trace-bind a 5 {:coor [2], :form-id -1100750367} nil]
             [:trace-bind b 6 {:coor [2], :form-id -1100750367} nil]
             [:trace-expr-exec 5 {:coor [2 2 1], :form-id -1100750367} nil]
             [:trace-expr-exec 6 {:coor [2 2 2], :form-id -1100750367} nil]
             [:trace-expr-exec 11 {:coor [2 2], :form-id -1100750367} nil]
             [:trace-expr-exec 11 {:coor [], :form-id -1100750367, :outer-form? true} nil]])

(def-instrumentation-test anonymous-fn-test "Test anonymous function instrumentation"

  :form (defn foo3 [xs]
          (->> xs (map (fn [i] (inc i))) doall))
  :run-form (foo3 [1 2 3])
  :should-return [2 3 4]
  ;; :print-collected? true
  :tracing ['[:trace-form-init {:def-kind :defn, :ns "hansel.instrument.forms-test", :form-id -313781675} (defn foo3 [xs] (->> xs (map (fn [i] (inc i))) doall)) nil]
            '[:trace-fn-call -313781675 "hansel.instrument.forms-test" "foo3" [[1 2 3]] nil]
            '[:trace-bind xs [1 2 3] {:coor nil, :form-id -313781675} nil]
            '[:trace-expr-exec [1 2 3] {:coor [3 1], :form-id -313781675} nil]
            '[:trace-expr-exec (2 3 4) {:coor [3 2], :form-id -313781675} nil]
            [:trace-fn-call -313781675 "hansel.instrument.forms-test" fn-str? [1] nil]
            '[:trace-bind i 1 {:coor [3 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 1 {:coor [3 2 1 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 2 {:coor [3 2 1 2], :form-id -313781675} nil]
            '[:trace-expr-exec 2 {:coor [], :form-id -313781675, :outer-form? true} nil]
            [:trace-fn-call -313781675 "hansel.instrument.forms-test" fn-str? [2] nil]
            '[:trace-bind i 2 {:coor [3 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 2 {:coor [3 2 1 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 3 {:coor [3 2 1 2], :form-id -313781675} nil]
            '[:trace-expr-exec 3 {:coor [], :form-id -313781675, :outer-form? true} nil]
            [:trace-fn-call -313781675 "hansel.instrument.forms-test" fn-str? [3] nil]
            '[:trace-bind i 3 {:coor [3 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 3 {:coor [3 2 1 2 1], :form-id -313781675} nil]
            '[:trace-expr-exec 4 {:coor [3 2 1 2], :form-id -313781675} nil]
            '[:trace-expr-exec 4 {:coor [], :form-id -313781675, :outer-form? true} nil]
            '[:trace-expr-exec (2 3 4) {:coor [3], :form-id -313781675} nil]
            '[:trace-expr-exec (2 3 4) {:coor [], :form-id -313781675, :outer-form? true} nil]])

(def-instrumentation-test multi-arity-function-definition-test "Test multiarity function instrumentation"
  
  :form (defn bar
          ([a] (bar a 10))
          ([a b] (+ a b)))
  :run-form (bar 5)
  ;; :print-collected? true
  :should-return 15
  :tracing '[[:trace-form-init {:def-kind :defn, :ns "hansel.instrument.forms-test", :form-id -1955739707} (defn bar ([a] (bar a 10)) ([a b] (+ a b))) nil]
             [:trace-fn-call -1955739707 "hansel.instrument.forms-test" "bar" [5] nil]
             [:trace-bind a 5 {:coor nil, :form-id -1955739707} nil]
             [:trace-expr-exec 5 {:coor [2 1 1], :form-id -1955739707} nil]
             [:trace-fn-call -1955739707 "hansel.instrument.forms-test" "bar" [5 10] nil]
             [:trace-bind a 5 {:coor nil, :form-id -1955739707} nil]
             [:trace-bind b 10 {:coor nil, :form-id -1955739707} nil]
             [:trace-expr-exec 5 {:coor [3 1 1], :form-id -1955739707} nil]
             [:trace-expr-exec 10 {:coor [3 1 2], :form-id -1955739707} nil]
             [:trace-expr-exec 15 {:coor [3 1], :form-id -1955739707} nil]
             [:trace-expr-exec 15 {:coor [], :form-id -1955739707, :outer-form? true} nil]
             [:trace-expr-exec 15 {:coor [2 1], :form-id -1955739707} nil]
             [:trace-expr-exec 15 {:coor [], :form-id -1955739707, :outer-form? true} nil]])

(defmulti a-multi-method :type)

(def-instrumentation-test defmethod-test "Test defmethod instrumentation"

  :form (defmethod a-multi-method :some-type
          [m]
          (:x m))
  :run-form (a-multi-method {:type :some-type :x 42})
  :should-return 42
  ;; :print-collected? true
  :tracing '[[:trace-form-init {:dispatch-val ":some-type", :def-kind :defmethod, :ns "hansel.instrument.forms-test", :form-id 1944849841} (defmethod a-multi-method :some-type [m] (:x m)) nil]
             [:trace-fn-call 1944849841 "hansel.instrument.forms-test" "a-multi-method" [{:type :some-type, :x 42}] nil]
             [:trace-bind m {:type :some-type, :x 42} {:coor nil, :form-id 1944849841} nil]
             [:trace-expr-exec {:type :some-type, :x 42} {:coor [4 1], :form-id 1944849841} nil]
             [:trace-expr-exec 42 {:coor [4], :form-id 1944849841} nil]
             [:trace-expr-exec 42 {:coor [], :form-id 1944849841, :outer-form? true} nil]])

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
  :tracing ['[:trace-form-init {:def-kind :defrecord, :ns "hansel.instrument.forms-test", :form-id -1038078878} (defrecord ARecord [n] FooP (proto-fn-1 [_] (inc n)) (proto-fn-2 [_] (dec n))) nil]
            #?(:clj [:trace-fn-call -1038078878 "hansel.instrument.forms-test" "proto-fn-1" [(->ARecord 5)] nil]
               :cljs [:trace-fn-call -1038078878 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1" [(->ARecord 5)] nil])
            '[:trace-expr-exec 5 {:coor [4 2 1], :form-id -1038078878} nil]
            '[:trace-expr-exec 6 {:coor [4 2], :form-id -1038078878} nil]
            '[:trace-expr-exec 6 {:coor [], :form-id -1038078878, :outer-form? true} nil]
            #?(:clj [:trace-fn-call -1038078878 "hansel.instrument.forms-test" "proto-fn-2" [(->ARecord 5)] nil]
               :cljs [:trace-fn-call -1038078878 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1" [(->ARecord 5)] nil])
            '[:trace-expr-exec 5 {:coor [5 2 1], :form-id -1038078878} nil]
            '[:trace-expr-exec 4 {:coor [5 2], :form-id -1038078878} nil]
            '[:trace-expr-exec 4 {:coor [], :form-id -1038078878, :outer-form? true} nil]])

(def-instrumentation-test deftype-test "Test deftype instrumentation"

  :form (deftype AType [n]
          FooP
          (proto-fn-1 [_] (inc n))
          (proto-fn-2 [_] (dec n)))
  :run-form (+ (proto-fn-1 (->AType 5)) (proto-fn-2 (->AType 5)))
  :should-return 10
  ;; :print-collected? true
  :tracing  [[:trace-form-init {:def-kind :deftype, :ns "hansel.instrument.forms-test", :form-id 279996188} '(deftype AType [n] FooP (proto-fn-1 [_] (inc n)) (proto-fn-2 [_] (dec n))) nil]
             #?(:clj [:trace-fn-call 279996188 "hansel.instrument.forms-test" "proto-fn-1" any? nil]
                :cljs [:trace-fn-call 279996188 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1" any? nil])
             [:trace-expr-exec 5 {:coor [4 2 1], :form-id 279996188} nil]
             [:trace-expr-exec 6 {:coor [4 2], :form-id 279996188} nil]
             [:trace-expr-exec 6 {:coor [], :form-id 279996188, :outer-form? true} nil]
             #?(:clj [:trace-fn-call 279996188 "hansel.instrument.forms-test" "proto-fn-2" any? nil]
                :cljs [:trace-fn-call 279996188 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1" any? nil])
             [:trace-expr-exec 5 {:coor [5 2 1], :form-id 279996188} nil]
             [:trace-expr-exec 4 {:coor [5 2], :form-id 279996188} nil]
             [:trace-expr-exec 4 {:coor [], :form-id 279996188, :outer-form? true} nil]])


(defrecord BRecord [n])

(def-instrumentation-test extend-protocol-test "Test extend-protocol instrumentation"

  :form (extend-protocol FooP
          BRecord
          (proto-fn-1 [this] (inc (:n this)))
          (proto-fn-2 [this] (dec (:n this))))
  :run-form (+ (proto-fn-1 (->BRecord 5)) (proto-fn-2 (->BRecord 5)))
  :should-return 10
  ;; :print-collected? true
  :tracing [[:trace-form-init {:def-kind :extend-protocol, :ns "hansel.instrument.forms-test", :form-id 969319502} '(extend-protocol FooP BRecord (proto-fn-1 [this] (inc (:n this))) (proto-fn-2 [this] (dec (:n this)))) nil]
            #?(:clj [:trace-fn-call 969319502 "hansel.instrument.forms-test" "proto-fn-1" [(->BRecord 5)] nil]
               :cljs [:trace-fn-call 969319502 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1" [(->BRecord 5)] nil])
            [:trace-bind 'this (->BRecord 5) any? nil]
            [:trace-expr-exec (->BRecord 5) {:coor [3 2 1 1], :form-id 969319502} nil]
            [:trace-expr-exec 5 {:coor [3 2 1], :form-id 969319502} nil]
            [:trace-expr-exec 6 {:coor [3 2], :form-id 969319502} nil]
            [:trace-expr-exec 6 {:coor [], :form-id 969319502, :outer-form? true} nil]
            #?(:clj [:trace-fn-call 969319502 "hansel.instrument.forms-test" "proto-fn-2" [(->BRecord 5)] nil]
               :cljs [:trace-fn-call 969319502 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1" [(->BRecord 5)] nil])
            [:trace-bind 'this (->BRecord 5) any? nil]
            [:trace-expr-exec (->BRecord 5) {:coor [4 2 1 1], :form-id 969319502} nil]
            [:trace-expr-exec 5 {:coor [4 2 1], :form-id 969319502} nil]
            [:trace-expr-exec 4 {:coor [4 2], :form-id 969319502} nil]
            [:trace-expr-exec 4 {:coor [], :form-id 969319502, :outer-form? true} nil]])

(defrecord CRecord [n])

(def-instrumentation-test extend-type-test "Test extend-type instrumentation"

  :form (extend-type CRecord            
          FooP
          (proto-fn-1 [this] (inc (:n this)))
          (proto-fn-2 [this] (dec (:n this))))
  :run-form (+ (proto-fn-1 (->CRecord 5)) (proto-fn-2 (->CRecord 5)))
  :should-return 10
  :print-collected? true
  :tracing [[:trace-form-init {:def-kind :extend-type, :ns "hansel.instrument.forms-test", :form-id -1521217400} '(extend-type CRecord FooP (proto-fn-1 [this] (inc (:n this))) (proto-fn-2 [this] (dec (:n this)))) nil]
            #?(:clj [:trace-fn-call -1521217400 "hansel.instrument.forms-test" "proto-fn-1" [(->CRecord 5)] nil]
               :cljs [:trace-fn-call -1521217400 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_1$arity$1" [(->CRecord 5)] nil])
            [:trace-bind 'this (->CRecord 5) any? nil]
            [:trace-expr-exec (->CRecord 5) {:coor [3 2 1 1], :form-id -1521217400} nil]
            [:trace-expr-exec 5 {:coor [3 2 1], :form-id -1521217400} nil]
            [:trace-expr-exec 6 {:coor [3 2], :form-id -1521217400} nil]
            [:trace-expr-exec 6 {:coor [], :form-id -1521217400, :outer-form? true} nil]
            #?(:clj [:trace-fn-call -1521217400 "hansel.instrument.forms-test" "proto-fn-2" [(->CRecord 5)] nil]
               :cljs [:trace-fn-call -1521217400 "hansel.instrument.forms-test" "-hansel$instrument$forms-test$FooP$proto_fn_2$arity$1" [(->CRecord 5)] nil])
            [:trace-bind 'this (->CRecord 5) any? nil]
            [:trace-expr-exec (->CRecord 5) {:coor [4 2 1 1], :form-id -1521217400} nil]
            [:trace-expr-exec 5 {:coor [4 2 1], :form-id -1521217400} nil]
            [:trace-expr-exec 4 {:coor [4 2], :form-id -1521217400} nil]
            [:trace-expr-exec 4 {:coor [], :form-id -1521217400, :outer-form? true} nil]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Only testing clojure.core.async/go on Clojure since we can't block in ClojureScript and our testing system ;;
;; doesn't support async yet.                                                                                 ;;
;; We aren't doing anything special for ClojureScript go blocks, so testing on one should be enough           ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn async-chan? [x]
     (instance? clojure.core.async.impl.channels.ManyToManyChannel x)))

#?(:clj
   (defn async-chan-vec? [v]
     (and (vector? v)
          (every? async-chan? v))))

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
     :tracing [[:trace-form-init {:form-id 1249185395, :ns "hansel.instrument.forms-test", :def-kind :defn} '(defn some-async-fn [] (let [in-ch (async/chan) out-ch (async/go (loop [] (when-some [x (async/<! in-ch)] x (recur))))] [in-ch out-ch])) nil]
               [:trace-fn-call 1249185395 "hansel.instrument.forms-test" "some-async-fn" [] nil]
               [:trace-expr-exec async-chan? {:coor [3 1 1], :form-id 1249185395} nil]
               [:trace-bind 'in-ch async-chan? {:coor [3], :form-id 1249185395} nil]
               [:trace-bind 'out-ch async-chan? {:coor [3], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan? {:coor [3 2 0], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan? {:coor [3 2 1], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan-vec? {:coor [3], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan-vec? {:coor [], :form-id 1249185395, :outer-form? true} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 1 2 1 1 1], :form-id 1249185395} nil]
               [:trace-expr-exec "hello" {:coor [3 1 3 1 2 1 1], :form-id 1249185395} nil]
               [:trace-bind 'x "hello" {:coor nil, :form-id 1249185395} nil]
               [:trace-expr-exec "hello" {:coor [3 1 3 1 2 2], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 1 2 1 1 1], :form-id 1249185395} nil]
               [:trace-expr-exec "bye" {:coor [3 1 3 1 2 1 1], :form-id 1249185395} nil]
               [:trace-bind 'x "bye" {:coor nil, :form-id 1249185395} nil]
               [:trace-expr-exec "bye" {:coor [3 1 3 1 2 2], :form-id 1249185395} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 1 2 1 1 1], :form-id 1249185395} nil]
               [:trace-expr-exec nil {:coor [3 1 3 1 2 1 1], :form-id 1249185395} nil]
               [:trace-expr-exec nil {:coor [3 1 3 1], :form-id 1249185395} nil]]))

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
     :print-collected? true
     :tracing [[:trace-form-init {:form-id 352274237, :ns "hansel.instrument.forms-test", :def-kind :defn} '(defn some-other-async-fn [] (let [in-ch (async/chan) out-ch (async/go-loop [] (when-some [x (async/<! in-ch)] x (recur)))] [in-ch out-ch])) nil]
               [:trace-fn-call 352274237 "hansel.instrument.forms-test" "some-other-async-fn" [] nil]
               [:trace-expr-exec async-chan? {:coor [3 1 1], :form-id 352274237} nil]
               [:trace-bind 'in-ch async-chan? {:coor [3], :form-id 352274237} nil]
               [:trace-bind 'out-ch async-chan? {:coor [3], :form-id 352274237} nil]
               [:trace-expr-exec async-chan? {:coor [3 2 0], :form-id 352274237} nil]
               [:trace-expr-exec async-chan? {:coor [3 2 1], :form-id 352274237} nil]
               [:trace-expr-exec async-chan-vec? {:coor [3], :form-id 352274237} nil]
               [:trace-expr-exec async-chan-vec? {:coor [], :form-id 352274237, :outer-form? true} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 2 1 1 1], :form-id 352274237} nil]
               [:trace-expr-exec "hello" {:coor [3 1 3 2 1 1], :form-id 352274237} nil]
               [:trace-bind 'x "hello" {:coor nil, :form-id 352274237} nil]
               [:trace-expr-exec "hello" {:coor [3 1 3 2 2], :form-id 352274237} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 2 1 1 1], :form-id 352274237} nil]
               [:trace-expr-exec "bye" {:coor [3 1 3 2 1 1], :form-id 352274237} nil]
               [:trace-bind 'x "bye" {:coor nil, :form-id 352274237} nil]
               [:trace-expr-exec "bye" {:coor [3 1 3 2 2], :form-id 352274237} nil]
               [:trace-expr-exec async-chan? {:coor [3 1 3 2 1 1 1], :form-id 352274237} nil]
               [:trace-expr-exec nil {:coor [3 1 3 2 1 1], :form-id 352274237} nil]]))
