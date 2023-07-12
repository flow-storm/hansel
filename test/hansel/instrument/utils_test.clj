(ns hansel.instrument.utils-test
  (:require [hansel.instrument.utils :as sut]
            [clojure.test :refer [testing is deftest]]
            [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow-server]))

(deftest source-form-test
  (let [boolean-src-loc '{:file "clojure/core.clj",
                          :form (defn boolean? "Return true if x is a Boolean" {:added "1.9"} [x] (instance? Boolean x)),
                          :line 521}]
    (testing "Retrieving clojure source form by var symbol"
      (is (= boolean-src-loc
             (sut/source-form 'clojure.core/boolean?))))

    (testing "Retrieving clojure source form by file, line and ns"
      (is (= boolean-src-loc
             (sut/source-form "clojure/core.clj" 521 "clojure.core"))))))

(deftest source-fn-cljs-test
  (shadow-server/start!)
  (.start (Thread. (fn [] (shadow/node-repl))))

  ;; wait for the repl
  (while (not= "42"
               (let [{:keys [results]} (shadow/cljs-eval :node-repl "42" {:ns 'cljs.user})]
                 (first results)))
    (Thread/sleep 500))

  (let [boolean-src-loc '{:file "cljs/core.cljs",
                          :form (defn boolean? "Return true if x is a Boolean" [x] (or (cljs.core/true? x) (cljs.core/false? x))),
                          :line 2242}]

    (testing "Retrieving clojurescript source form by var symbol"
      (is (= boolean-src-loc
             (sut/source-fn-cljs 'cljs.core/boolean? :node-repl))))

    (testing "Retrieving clojurescript source form by file, line and ns"
      (is (= boolean-src-loc
             (sut/source-fn-cljs "cljs/core.cljs" 2242 "cljs.core" :node-repl)))))

  (shadow/cljs-eval :node-repl ":repl/quit" {:ns 'cljs.user}))
