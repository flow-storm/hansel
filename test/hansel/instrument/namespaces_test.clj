(ns hansel.instrument.namespaces-test
  (:require [hansel.api :as hansel]
            [cljs.main :as cljs-main]
            [clojure.java.io :as io]
            [hansel.instrument.tester :as tester]
            [clojure.test :refer [deftest is testing] :as t]
            [clojure.set :as set]
            [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow-server]))


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
    (with-redefs [trace-form-init (fn [_] (swap! trace-count inc))
                  trace-fn-call (fn [_] (swap! trace-count inc) )
                  trace-fn-return (fn [data] (swap! trace-count inc) (:return data))
                  trace-bind (fn [_] (swap! trace-count inc))
                  trace-expr-exec (fn [data] (swap! trace-count inc) (:result data))]

      (testing "Instrument clojure vars by symbol "
        (let [inst-set (into #{} (hansel/instrument-var-clj 'clojure.set/join inst-config))]
          ;; call the instrumented function
          (set/join #{{:id 1 :name "john"}} #{{:id 1 :age 39}})

          (is (= 61 @trace-count) "Instrumented var should generate traces")

          (is (= '#{{:file "clojure/set.clj", :line 115, :var-symb clojure.set/join}} inst-set)
              "Instrumeting var with deep? false should only instrument required var")))

      (testing "Uninstrument clojure vars by symbol"
        (let [un-inst-set (into #{} (hansel/uninstrument-var-clj 'clojure.set/join))]
          (is (= '#{{:file "clojure/set.clj", :line 115, :var-symb clojure.set/join}} un-inst-set)
              "Uninstrumeting var with deep? false should only instrument required var")

          ;; call the UNinstrumented function
          (set/join #{{:id 1 :name "john"}} #{{:id 1 :age 39}})

          (is (= 61 @trace-count) "Uninstrumented var should NOT generate traces")))

      (testing "Instrument clojure vars deeply"
        (let [vars-expected-set '#{{:file "clojure/set.clj", :line 13, :var-symb clojure.set/bubble-max-key}
                                   {:file "clojure/set.clj", :line 33, :var-symb clojure.set/intersection}
                                   {:file "clojure/set.clj", :line 78, :var-symb clojure.set/rename-keys}
                                   {:file "clojure/set.clj", :line 95, :var-symb clojure.set/index}
                                   {:file "clojure/set.clj", :line 106, :var-symb clojure.set/map-invert}
                                   {:file "clojure/set.clj", :line 115, :var-symb clojure.set/join}}
              inst-set (->> (hansel/instrument-var-clj 'clojure.set/join (assoc inst-config
                                                                                :deep? true))
                            (into #{}))]

          (is (= vars-expected-set inst-set)
              "Instrumeting var with deep? true should instrument all sub-vars")

          ;; call the instrumented function
          (set/join #{{:id 1 :name "john"}} #{{:id 1 :age 39}})

          (is (= 150 @trace-count) "Re instrumented var should generate traces")

          ;; finally leave the fn uninstrumented
          (let [un-inst-set (into #{} (hansel/uninstrument-var-clj 'clojure.set/join {:deep? true}))]
            (is (= vars-expected-set un-inst-set)
                "Uninstrumeting var with deep? true should uninstrument all sub-vars")))))))

(deftest instrument-var-shadow-cljs

  (shadow-server/start!)
  (.start (Thread. (fn [] (shadow/node-repl))))

  ;; wait for the repl
  (while (not= "42"
               (let [{:keys [results]} (shadow/cljs-eval :node-repl "42" {:ns 'cljs.user})]
                 (first results)))
    (Thread/sleep 500))

  ;; setup the ClojureScript side first
  (shadow/cljs-eval :node-repl "(require '[hansel.instrument.tester :as tester] :reload-all)" {:ns 'cljs.user})
  (shadow/cljs-eval :node-repl "(def traces-cnt1 (atom {:trace-form-init 0, :trace-fn-call 0, :trace-fn-return 0, :trace-expr-exec 0, :trace-bind 0}))" {:ns 'cljs.user})

  (shadow/cljs-eval :node-repl "(defn count-form-init [_] (swap! traces-cnt1 update :trace-form-init inc))" {:ns 'cljs.user})
  (shadow/cljs-eval :node-repl "(defn count-fn-call [_] (swap! traces-cnt1 update :trace-fn-call inc))" {:ns 'cljs.user})
  (shadow/cljs-eval :node-repl "(defn count-fn-return [{:keys [return] :as data}] (swap! traces-cnt1 update :trace-fn-return inc) return)" {:ns 'cljs.user})
  (shadow/cljs-eval :node-repl "(defn count-expr-exec [{:keys [result] :as data}] (swap! traces-cnt1 update :trace-expr-exec inc) result)" {:ns 'cljs.user})
  (shadow/cljs-eval :node-repl "(defn count-bind [_] (swap! traces-cnt1 update :trace-bind inc))" {:ns 'cljs.user})

  (let [inst-set (->> (hansel/instrument-var-shadow-cljs
                       'hansel.instrument.tester/factorial
                       '{:trace-form-init cljs.user/count-form-init
                         :trace-fn-call cljs.user/count-fn-call
                         :trace-fn-return cljs.user/count-fn-return
                         :trace-expr-exec cljs.user/count-expr-exec
                         :trace-bind cljs.user/count-bind
                         :build-id :node-repl})
                      (into #{}))
        expected-set '#{{:file "hansel/instrument/tester.cljs", :line 3, :var-symb hansel.instrument.tester/factorial}}]
    (is (= expected-set inst-set) "Shallow instrumentation should only instrument requested var"))

  (testing "ClojureScript var instrumentation/uninstrumentation"

    (is (= 120
           (some-> (shadow/cljs-eval :node-repl "(tester/factorial 5)" {:ns 'cljs.user})
                   :results
                   first
                   read-string))
        "Instrumented function should return the same as the original one")

    (is (= {:trace-bind 6
            :trace-expr-exec 43
            :trace-fn-call 6
            :trace-fn-return 6
            :trace-form-init 1}
           (some-> (shadow/cljs-eval :node-repl "@traces-cnt1" {:ns 'cljs.user})
                   :results
                   first
                   read-string))
        "Traces count should be correct")

    (let [expected-set '#{{:file "hansel/instrument/tester.cljs", :line 22, :var-symb hansel.instrument.tester/add}
                          {:file "hansel/instrument/tester.cljs", :line 25, :var-symb hansel.instrument.tester/sub}
                          {:file "hansel/instrument/tester.cljs", :line 12, :var-symb hansel.instrument.tester/do-it}
                          {:file "hansel/instrument/tester.cljs", :line 48, :var-symb hansel.instrument.tester/other-function}
                          {:file "hansel/instrument/tester.cljs", :line 52, :var-symb hansel.instrument.tester/boo}
                          {:file "hansel/instrument/tester.cljs", :line 8, :var-symb hansel.instrument.tester/multi-arity}
                          {:file "hansel/instrument/tester.cljs", :line 28, :var-symb hansel.instrument.tester/->ARecord}}
          inst-set (->> (hansel/instrument-var-shadow-cljs
                         'hansel.instrument.tester/boo
                         '{:trace-form-init cljs.user/count-form-init
                           :trace-fn-call cljs.user/count-fn-call
                           :trace-fn-return cljs.user/count-fn-return
                           :trace-expr-exec cljs.user/count-expr-exec
                           :trace-bind cljs.user/count-bind
                           :build-id :node-repl
                           :deep? true})
                        (into #{}))]
      (is (= expected-set inst-set)
          "Deep instrumentation should instrument all"))

    (shadow/cljs-eval :node-repl ":repl/quit" {:ns 'cljs.user})))

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

        (let [result-before-instrumentation (tester/boo [1 "hello" 5])
              {:keys [inst-fns affected-namespaces]} (hansel/instrument-namespaces-clj #{"hansel.instrument.tester"}
                                                                                       `{:trace-form-init trace-form-init
                                                                                         :trace-fn-call trace-fn-call
                                                                                         :trace-fn-return trace-fn-return
                                                                                         :trace-expr-exec trace-expr-exec
                                                                                         :trace-bind trace-bind
                                                                                         :verbose? true})]

          (is (= '#{[hansel.instrument.tester/->ARecord 1]
                    [hansel.instrument.tester/add 1]
                    [hansel.instrument.tester/boo 1]
                    [hansel.instrument.tester/do-it 1]
                    [hansel.instrument.tester/dummy-sum-macro 4]
                    [hansel.instrument.tester/factorial 1]
                    [hansel.instrument.tester/map->ARecord 1]
                    [hansel.instrument.tester/other-function 2]
                    [hansel.instrument.tester/sub 1]}
                 inst-fns)
              "Namespace instrumentation should collect instrumented fns")

          (is (= '#{hansel.instrument.tester}
                 affected-namespaces)
              "Namespace instrumentation should collect affected-namespaces")

          (is (= result-before-instrumentation (tester/boo [1 "hello" 5]))
              "Instrumented code should return the same as the original code")

          (is (= @traces-counts
                 {:trace-form-init 10,
                  :trace-fn-call 20,
                  :trace-fn-return 20,
                  :trace-expr-exec 817,
                  :trace-bind 236})
              "Traces count differ"))))))

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
                                            :excluding-ns #{"cljs.core" "cljs.vendor.clojure.tools.reader.default-data-readers"}})

        (is (= "Factorial of 5 is : 120\n"
               (with-out-str (cljs-main "-t" "nodejs" (.toString (io/file (io/resource "org/foo/myscript.cljs"))))))
            "Instrumented code should return the same as the original code")

        (is (->> @traces-counts
                 vals
                 (every? pos?))
            "It should trace")

        ;; uninstrument everything
        (hansel/uninstrument-namespaces-clj #{"cljs."}
                                            {:excluding-ns #{"cljs.core" "cljs.vendor.clojure.tools.reader.default-data-readers"}})))))

;; This test works but sometimes it dead locks
(deftest shadow-cljs-full-tester-instrumentation
  (testing "Full ClojureScript hansel.instrument.tester instrumentation via shadow-cljs"

    (shadow-server/start!)
    (.start (Thread. (fn [] (shadow/node-repl))))

    ;; wait for the repl
    (while (not= "42"
               (let [{:keys [results]} (shadow/cljs-eval :node-repl "42" {:ns 'cljs.user})]
                 (first results)))
    (Thread/sleep 500))

    ;; setup the ClojureScript side first
    (shadow/cljs-eval :node-repl "(require '[hansel.instrument.tester :as tester] :reload-all)" {:ns 'cljs.user})

    (shadow/cljs-eval :node-repl "(def traces-cnt2 (atom {:trace-form-init 0, :trace-fn-call 0, :trace-fn-return 0, :trace-expr-exec 0, :trace-bind 0}))" {:ns 'cljs.user})

    (shadow/cljs-eval :node-repl "(defn count-form-init [_] (swap! traces-cnt2 update :trace-form-init inc))" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-fn-call [_] (swap! traces-cnt2 update :trace-fn-call inc))" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-fn-return [{:keys [return] :as data}] (swap! traces-cnt2 update :trace-fn-return inc) return)" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-expr-exec [{:keys [result] :as data}] (swap! traces-cnt2 update :trace-expr-exec inc) result)" {:ns 'cljs.user})
    (shadow/cljs-eval :node-repl "(defn count-bind [_] (swap! traces-cnt2 update :trace-bind inc))" {:ns 'cljs.user})

    (let [result-before-instrumentation (some-> (shadow/cljs-eval :node-repl "(tester/boo [1 \"hello\" 4])" {:ns 'cljs.user})
                                                :results
                                                first
                                                read-string)
          {:keys [inst-fns affected-namespaces]} (hansel/instrument-namespaces-shadow-cljs
                                                  #{"hansel.instrument.tester"}
                                                  '{:trace-form-init cljs.user/count-form-init
                                                    :trace-fn-call cljs.user/count-fn-call
                                                    :trace-fn-return cljs.user/count-fn-return
                                                    :trace-expr-exec cljs.user/count-expr-exec
                                                    :trace-bind cljs.user/count-bind
                                                    :build-id :node-repl
                                                    :verbose? true})]

      (is (= '#{[hansel.instrument.tester/add 1]
                [hansel.instrument.tester/boo 1]
                [hansel.instrument.tester/do-it 1]
                [hansel.instrument.tester/factorial 1]
                [hansel.instrument.tester/other-function 2]
                [hansel.instrument.tester/sub 1]
                [hansel.instrument.tester/multi-arity 1]
                [hansel.instrument.tester/multi-arity 2]
                [hansel.instrument.tester/-cljs$user$Adder$add$arity$1 1]
                [hansel.instrument.tester/-cljs$user$Suber$sub$arity$1 1]}
             inst-fns)
          "Namespace instrumentation should collect instrumented fns")

      (is (= '#{hansel.instrument.tester}
             affected-namespaces)
          "Namespace instrumentation should collect affected-namespaces")

      (is (= result-before-instrumentation
             (some-> (shadow/cljs-eval :node-repl "(tester/boo [1 \"hello\" 4])" {:ns 'cljs.user})
                     :results
                     first
                     read-string))
          "Instrumented function should return the same as the original one")

      (is (= {:trace-bind 235
              :trace-expr-exec 805
              :trace-fn-call 20
              :trace-fn-return 20
              :trace-form-init 11}
             (some-> (shadow/cljs-eval :node-repl "@traces-cnt2" {:ns 'cljs.user})
                     :results
                     first
                     read-string))
          "Traces count should be correct")

      (hansel/uninstrument-namespaces-shadow-cljs
       #{"hansel.instrument.tester"}
       {:build-id :node-repl})

      (shadow/cljs-eval :node-repl ":repl/quit" {:ns 'cljs.user}))))
