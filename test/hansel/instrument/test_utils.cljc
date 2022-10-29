(ns hansel.instrument.test-utils
  (:require [hansel.instrument.runtime]
            [cljs.core.match :refer-macros [match]]
            #?@(:clj [[hansel.utils :as utils]
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
                               :env env}
                              form))))

#?(:clj
   (defmacro def-instrumentation-test [tname tdesc & {:keys [form print-collected? run-form should-return tracing]}]
     (let [collected-traces-symb (gensym "collected-traces")
           matches-tests (->> tracing
                              (map-indexed (fn [i t]
                                             `(let [coll-trace# (nth (deref ~collected-traces-symb) ~i)]
                                                (clojure.test/is
                                                 (~'match [coll-trace#]
                                                        [~t] true
                                                        :else false)
                                                 (utils/format "Trace should match (let [t %s] (clojure.core.match/match [t] \n [%s] true :else false))" coll-trace# ~(str t)))))))]
       `(do        
          (clojure.test/deftest ~tname
            (clojure.test/testing ~tdesc
              (let [~collected-traces-symb (atom [])]
                (with-redefs [hansel.instrument.test-utils/trace-form-init (fn [data# rt-ctx#] (swap! ~collected-traces-symb conj [:trace-form-init data# rt-ctx#]))
                              hansel.instrument.test-utils/trace-fn-call (fn [data# rt-ctx#] (swap! ~collected-traces-symb conj [:trace-fn-call data# rt-ctx#]) )
                              hansel.instrument.test-utils/trace-fn-return (fn [data# rt-ctx#] (swap! ~collected-traces-symb conj [:trace-fn-return data# rt-ctx#]) (:return data#) )
                              hansel.instrument.test-utils/trace-bind (fn [data# rt-ctx#] (swap! ~collected-traces-symb conj [:trace-bind data# rt-ctx#]))
                              hansel.instrument.test-utils/trace-expr-exec (fn [data# rt-ctx#] (swap! ~collected-traces-symb conj [:trace-expr-exec data# rt-ctx#]) (:result data#))]

                  (instrument ~form)
                  
                  (let [form-return# ~run-form]
                    
                    (when ~print-collected?
                      (println "Collected traces" (prn-str (deref ~collected-traces-symb))))

                    (clojure.test/is (= form-return# ~should-return) "Instrumentation should not break the form")

                    (clojure.test/is (= ~(count tracing)
                                        (count (deref ~collected-traces-symb)))
                                     "Collected traces should match with provided traces")

                    ~@matches-tests
                    )))))))))

#?(:clj (defn read-eq-guard [form]
          `(~'_ :guard (fn [x#] (= x# ~form)))))
