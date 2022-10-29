(ns hansel.instrument.test-utils
  (:require [hansel.instrument.runtime]
            #?@(:clj [[hansel.instrument.forms :as inst-forms]])))

(defn match [v1 v2]
  (and (= (count v1) (count v2))
       (every? true? (map (fn [x1 x2]
                            (if (fn? x2)
                              (x2 x1)
                              (= x1 x2)))
                          v1 v2))))

(declare trace-form-init)
(declare trace-fn-call)
(declare trace-bind)
(declare trace-expr-exec)

#?(:clj
   (defmacro instrument [form]     
     (let [env &env]
       (inst-forms/instrument {:trace-form-init 'hansel.instrument.test-utils/trace-form-init
                               :trace-fn-call 'hansel.instrument.test-utils/trace-fn-call
                               :trace-bind 'hansel.instrument.test-utils/trace-bind
                               :trace-expr-exec 'hansel.instrument.test-utils/trace-expr-exec
                               :env env}
                             form))))

#?(:clj
   (defmacro def-instrumentation-test [tname tdesc & {:keys [form print-collected? run-form should-return tracing]}]
     `(do        
        (clojure.test/deftest ~tname
          (clojure.test/testing ~tdesc
            (let [collected-traces# (atom [])]
              (with-redefs [hansel.instrument.test-utils/trace-form-init (fn [& args#] (swap! collected-traces# conj (into [:trace-form-init] args#)))
                            hansel.instrument.test-utils/trace-fn-call (fn [& args#] (swap! collected-traces# conj (into [:trace-fn-call] args#)) )
                            hansel.instrument.test-utils/trace-bind (fn [& args#] (swap! collected-traces# conj (into [:trace-bind] args#)))
                            hansel.instrument.test-utils/trace-expr-exec (fn [r# & args#] (swap! collected-traces# conj (into [:trace-expr-exec r#] args#)) r#)]

                (instrument ~form)
                
                (let [form-return# ~run-form]
                  
                  (when ~print-collected?
                    (println "Collected traces" (prn-str @collected-traces#)))

                  (clojure.test/is (= form-return# ~should-return) "Instrumentation should not break the form")

                  (clojure.test/is (= (count ~tracing)
                                      (count @collected-traces#))
                                   "Collected traces should match with provided traces")

                  (doseq [[coll-trace# target-trace#] (map vector @collected-traces# ~tracing)]
                    (println "Matching " (pr-str [coll-trace# target-trace#]))
                    (println "Result " (match coll-trace# target-trace#))
                    (clojure.test/is (match coll-trace# target-trace#) "Trace should match"))))))))))
