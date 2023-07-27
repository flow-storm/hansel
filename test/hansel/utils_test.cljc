(ns hansel.utils-test
  (:require [hansel.utils :refer [get-form-at-coord obj-coord]]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is] :include-macros true])))

(deftest get-form-at-coord-test
  (testing "Get form at coord"
    (is (= '(* 3 4) (get-form-at-coord '(+ 1 2 (* 3 4)) [3])))
    (let [frm '(let [[_ k-or-v hash-str] (re-find #"(.)(.+)" cc)
                     ks (if (map? form) (keys form) form) ;; could be a map or a set
                     hash->keys (reduce (fn [r k]
                                          (assoc r (str (clojure-form-source-hash (pr-str k))) k) )
                                        {}
                                        ks)
                     k (hash->keys hash-str)]
                 (if (= k-or-v "K")
                   (get-form-at-coord k rcoord)
                   (get-form-at-coord (get form k) rcoord)))]
      (is (= 'hash-str (get-form-at-coord frm [1 0 2])))
      (is (= '(pr-str k) (get-form-at-coord frm [1 5 1 2 2 1 1]))))

    (is (= (get-form-at-coord '(let [a {"hello" 10}] a)
                              [1 1 (obj-coord "K" "hello")])
           "hello"))

    (is (= 10 (get-form-at-coord '(let [a {"hello" 10}] a)
                                 [1 1 (obj-coord "V" "hello")])))

    (is (= :other-element
           (get-form-at-coord '(let [a #{:element :other-element :and-another}] a)
                              [1 1 (obj-coord "K" :other-element)])))

    (is (= 2
           (get-form-at-coord '(let [a #{{1 2} {2 4} {3 6}}] a)
                              [1 1 (obj-coord "K" {1 2}) (obj-coord "V" 1)])))))
