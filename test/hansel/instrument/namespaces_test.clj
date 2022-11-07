(ns hansel.instrument.namespaces-test
  (:require [hansel.api :as hansel]
            [cljs.main :as cljs-main]
            [clojure.java.io :as io]
            [hansel.instrument.tester :as tester]
            [clojure.test :refer [deftest is testing] :as t]
            [clojure.set :as set]
            #_[shadow.cljs.devtools.api :as shadow]
            #_[shadow.cljs.devtools.server :as shadow-server]))


(declare trace-form-init)
(declare trace-fn-call)
(declare trace-fn-return)
(declare trace-expr-exec)
(declare trace-bind)

(defn cljs-main [& args]
    (with-redefs [clojure.core/shutdown-agents (fn [] nil)]
      (apply cljs-main/-main args)))

(deftest instrument-var-clj
  (let [trace-count (atom 0)
        inst-config `{:trace-form-init trace-form-init
                      :trace-fn-call trace-fn-call
                      :trace-fn-return trace-fn-return
                      :trace-expr-exec trace-expr-exec
                      :trace-bind trace-bind}]
    (testing "Clojure var instrumentation/uninstrumentation"
      (with-redefs [trace-form-init (fn [_] (swap! trace-count inc))
                    trace-fn-call (fn [_] (swap! trace-count inc) )
                    trace-fn-return (fn [data] (swap! trace-count inc) (:return data))
                    trace-bind (fn [_] (swap! trace-count inc))
                    trace-expr-exec (fn [data] (swap! trace-count inc) (:result data))]

        ;; instrument first time
        (hansel/instrument-var-clj 'clojure.set/difference inst-config)
        ;; call the instrumented function
        (set/difference #{1 2 3} #{2})

        (is (= 15 @trace-count) "Instrumented var should generate traces")

        ;; UNinstrument
        (hansel/instrument-var-clj 'clojure.set/difference {:uninstrument? true})
        ;; call the UNinstrumented function
        (set/difference #{1 2 3} #{2})

        (is (= 15 @trace-count) "Uninstrumented var should NOT generate traces")

        ;; Instrument again (to check we didn't lost anything and we can do this indefinetly)
        (hansel/instrument-var-clj 'clojure.set/difference inst-config)
        ;; call the instrumented function
        (set/difference #{1 2 3} #{2})

        (is (= 30 @trace-count) "Re instrumented var should generate traces")

        ;; finally leave the fn uninstrumented
        (hansel/instrument-var-clj 'clojure.set/difference {:uninstrument? true})))))

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
                               :trace-fn-return 0})]
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

        (is (->> @traces-counts
                 vals
                 (every? pos?))
            "It should trace")))))

;; This test works but sometimes it dead locks
#_(deftest shadow-cljs-full-cljs-set-instrumentation
  (testing "Full ClojureScript cljs.set instrumentation via shadow-cljs"

    (shadow-server/start!)
    (.start (Thread. (fn [] (shadow/node-repl))))

    ;; wait for the repl
    (while (not= "42" (first (:results (shadow/cljs-eval :node-repl "42" {:ns 'cljs.user}))))
      (Thread/sleep 500))

    ;; setup the ClojureScript side first
    (shadow/cljs-eval :node-repl "(require '[clojure.set :as set])" {:ns 'cljs.user})

    (shadow/cljs-eval :node-repl "(def traces-count (atom {:trace-form-init 0, :trace-fn-call 0, :trace-fn-return 0, :trace-expr-exec 0, :trace-bind 0}))" {:ns 'cljs.user})

    (shadow/cljs-eval :node-repl "(defn count-form-init [_] (swap! traces-count update :trace-form-init inc))" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-fn-call [_] (swap! traces-count update :trace-fn-call inc))" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-fn-return [{:keys [return] :as data}] (swap! traces-count update :trace-fn-return inc) return)" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-expr-exec [{:keys [result] :as data}] (swap! traces-count update :trace-expr-exec inc) result)" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-bind [_] (swap! traces-count update :trace-bind inc))" {:ns 'cljs.user})

    (hansel/instrument-namespaces-shadow-cljs
     #{"clojure.set"}
     '{:trace-form-init cljs.user/count-form-init
       :trace-fn-call cljs.user/count-fn-call
       :trace-fn-return cljs.user/count-fn-return
       :trace-expr-exec cljs.user/count-expr-exec
       :trace-bind cljs.user/count-bind
       :uninstrument? false
       :build-id :node-repl
       :excluding-fns #{clojure.set/intersection}})

    (is (= #{1 3}
           (-> (shadow/cljs-eval :node-repl "(set/difference #{1 2 3} #{2})" {:ns 'cljs.user})
               :results
               first
               read-string))
        "Instrumented function should return the same as the original one")

    (is (= {:trace-bind 338
            :trace-expr-exec 658
            :trace-fn-call 121
            :trace-fn-return 121
            :trace-form-init 13}
           (-> (shadow/cljs-eval :node-repl "@traces-count" {:ns 'cljs.user})
               :results
               first
               read-string))
        "Traces count should be correct")))
