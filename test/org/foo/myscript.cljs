(ns org.foo.myscript)

(defn factorial [n]
  (if (zero? n)
    1
    (* n (factorial (dec n)))))

(js/console.log "Factorial of 5 is :" (factorial 5))
