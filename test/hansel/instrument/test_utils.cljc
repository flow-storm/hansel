(ns hansel.instrument.test-utils
  (:require [hansel.instrument.runtime]
            [cljs.core.match :refer-macros [match]]
            #?@(:clj [[hansel.utils :as utils]
                      [hansel.instrument.utils :as inst-utils]
                      [hansel.instrument.forms :as inst-forms]])))

;; just for clj-kondo
(comment match)
(declare trace-form-init)
(declare trace-fn-call)
(declare trace-fn-return)
(declare trace-bind)
(declare trace-expr-exec)

#?(:clj
   (defmacro instrument [form]     
     (let [env &env]
       (inst-forms/instrument {:trace-form-init 'hansel.instrument.test-utils/trace-form-init
                               :trace-fn-call 'hansel.instrument.test-utils/trace-fn-call
                               :trace-fn-return 'hansel.instrument.test-utils/trace-fn-return
                               :trace-bind 'hansel.instrument.test-utils/trace-bind
                               :trace-expr-exec 'hansel.instrument.test-utils/trace-expr-exec
                               :compiler (inst-utils/compiler-from-env env)
                               ;; this is hacky, but just to make the forms/instrument assert pass
                               ;; for tests
                               :build-id :node-repl}
                              form))))

#?(:clj
   (defmacro def-instrumentation-test [tname tdesc & {:keys [form print-collected? run-form should-return tracing unsorted-tracing]}]
     (let [collected-traces-symb (gensym "collected-traces")
           matches-tests (when tracing
                           (->> tracing
                                (map-indexed (fn [i t]
                                               `(let [coll-trace# (nth (deref ~collected-traces-symb) ~i)]
                                                  (clojure.test/is
                                                   (~'match [coll-trace#]
                                                    [~t] true
                                                    :else false)
                                                   (utils/format "Trace should match (let [t %s] (clojure.core.match/match [t] \n [%s] true :else false))" coll-trace# ~(str t))))))))]
       `(do        
          (clojure.test/deftest ~tname
            (clojure.test/testing ~tdesc
              (let [~collected-traces-symb (atom [])]
                (with-redefs [hansel.instrument.test-utils/trace-form-init (fn [data#] (swap! ~collected-traces-symb conj [:trace-form-init data#]))
                              hansel.instrument.test-utils/trace-fn-call (fn [data#] (swap! ~collected-traces-symb conj [:trace-fn-call data#]) )
                              hansel.instrument.test-utils/trace-fn-return (fn [data#] (swap! ~collected-traces-symb conj [:trace-fn-return data#]) (:return data#))
                              hansel.instrument.test-utils/trace-bind (fn [data#] (swap! ~collected-traces-symb conj [:trace-bind data#]))
                              hansel.instrument.test-utils/trace-expr-exec (fn [data#] (swap! ~collected-traces-symb conj [:trace-expr-exec data#]) (:result data#))]

                  (instrument ~form)
                  
                  (let [form-return# ~run-form]
                    
                    (when ~print-collected?
                      (println "Collected traces" (prn-str (deref ~collected-traces-symb))))

                    (clojure.test/is (= form-return# ~should-return) "Instrumentation should not break the form")

                    (clojure.test/is (= ~(if tracing (count tracing) (count unsorted-tracing))
                                        (count (deref ~collected-traces-symb)))
                                     "Collected traces should match with provided traces")

                    (when-let [ut# ~unsorted-tracing]
                      (clojure.test/is (= (into #{} (deref ~collected-traces-symb)) ut#) "Unsorted tracing should match"))
                    
                    ~@matches-tests)))))))))

#?(:clj (defn read-eq-guard [form]
          `(~'_ :guard (fn [x#] (= x# ~form)))))
