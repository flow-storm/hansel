(ns hansel.instrument.namespaces-test
  (:require [hansel.api :as hansel]
            [cljs.main :as cljs-main]
            [clojure.java.io :as io]
            [hansel.instrument.tester :as tester]
            [clojure.test :refer [deftest is testing] :as t]))


(declare trace-form-init)
(declare trace-fn-call)
(declare trace-fn-return)
(declare trace-expr-exec)
(declare trace-bind)

(defn cljs-main [& args]
    (with-redefs [clojure.core/shutdown-agents (fn [] nil)]
      (apply cljs-main/-main args)))

(deftest clojure-full-tester-namespace-instrumentation
  (testing "Full tester namespaces instrumentation"
    (let [traces-counts (atom {:trace-form-init 0
                               :trace-fn-call 0
                               :trace-fn-return 0
                               :trace-expr-exec 0
                               :trace-bind 0})]
      (with-redefs [trace-form-init (fn [_] (swap! traces-counts update :trace-form-init inc))
                    trace-fn-call (fn [_] (swap! traces-counts update :trace-fn-call inc) )
                    trace-fn-return (fn [data] (swap! traces-counts update :trace-fn-return inc) (:return data))
                    trace-bind (fn [_] (swap! traces-counts update :trace-bind inc))
                    trace-expr-exec (fn [data] (swap! traces-counts update :trace-expr-exec inc) (:result data))]

        (hansel/instrument-namespaces-clj #{"hansel.instrument.tester"}
                                          `{:trace-form-init trace-form-init
                                            :trace-fn-call trace-fn-call
                                            :trace-fn-return trace-fn-return
                                            :trace-expr-exec trace-expr-exec
                                            :trace-bind trace-bind
                                            :verbose? true
                                            :uninstrument? false})

        (is (= 6208 (tester/boo [1 "hello" 5]))
            "Instrumented code should return the same as the original code")

        (is (= @traces-counts
               {:trace-form-init 10,
                :trace-fn-call 20,
                :trace-fn-return 20,
                :trace-expr-exec 817,
                :trace-bind 236})
            "Traces count differ")))))

(deftest clojure-light-clojurescript-namespaces-instrumentation
  (testing "Light ClojureScript namespaces instrumentation"
    (let [traces-counts (atom {:trace-form-init 0
                               :trace-fn-call 0
                               :trace-fn-return 0
                               :trace-expr-exec 0
                               :trace-bind 0})]
      (with-redefs [trace-form-init (fn [_] (swap! traces-counts update :trace-form-init inc))
                    trace-fn-call (fn [_] (swap! traces-counts update :trace-fn-call inc) )
                    trace-fn-return (fn [data] (swap! traces-counts update :trace-fn-return inc) (:return data))
                    trace-bind (fn [_] (swap! traces-counts update :trace-bind inc))
                    trace-expr-exec (fn [data] (swap! traces-counts update :trace-expr-exec inc) (:result data))]

        (hansel/instrument-namespaces-clj #{"cljs."}
                                          `{:trace-form-init trace-form-init
                                            :trace-fn-call trace-fn-call
                                            :trace-fn-return trace-fn-return
                                            :excluding-ns #{"cljs.core"}
                                            :uninstrument? false})

        (is (= "Factorial of 5 is : 120\n"
               (with-out-str (cljs-main "-t" "nodejs" (.toString (io/file (io/resource "org/foo/myscript.cljs"))))))
            "Instrumented code should return the same as the original code")

        (is (= @traces-counts
               {:trace-form-init 1764,
                :trace-fn-call   3371273, ;; TODO: investigate why fn returns are lower than fn calls
                :trace-fn-return 3371273,
                :trace-expr-exec 0,
                :trace-bind 0})
            "Traces count differ")))))
