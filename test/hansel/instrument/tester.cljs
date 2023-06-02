(ns hansel.instrument.tester)

(defn factorial [n]
  (if (zero? n)
    1
    (* n (factorial (dec n)))))

(defn multi-arity
  ([a] (multi-arity a 10))
  ([a b] (+ a b)))

(defmulti do-it type)

(defmethod do-it js/Number
  [l]
  (factorial l))

(defmethod do-it js/String
  [s]
  (count s))

(defprotocol Adder
  (add [x]))

(defprotocol Suber
  (sub [x]))

(defrecord ARecord [n]

  Adder
  (add [_] (+ n 1000)))

(extend-protocol Adder

  number
  (add [l] (+ l 5)))

(extend-type number

  Suber
  (sub [l] (- l 42)))

(extend-type ARecord

  Suber
  (sub [r] (+ 42 (* 2 32))))

(def other-function
  (fn [a b]
    (+ a b 10)))

(defn boo [xs]
  (let [a 25
        yy (other-function 4 5)
        b (multi-arity a)
        m {:a 5 :b [1 2 3]}
        mm (assoc m :c 10)
        j (loop [i 100
                 sum 0]
            (if (> i 0)
              (recur (dec i) (+ sum i))
              sum))
        c (+ a b 7)
        d (add (->ARecord 5))
        sum (->> xs
                 (map (fn [x] (+ 1 (do-it x))))
                 (reduce + ))
        after-add (add sum)
        after-sub (sub after-add)
        final (+ c d j after-add after-sub)]
    final))
