(ns hansel.utils  
  #?(:clj (:require [clojure.string :as str]))
  #?(:clj (:refer-clojure :exclude [format]))
  #?(:cljs (:require [goog.string :as gstr]
                     [goog.string.format]
                     [clojure.string :as str])))

(defn format [& args]
  #?(:clj (apply clojure.core/format args)
     :cljs (apply gstr/format args)))

(defn merge-meta

  "Non-throwing version of (vary-meta obj merge metamap-1 metamap-2 ...).
  Like `vary-meta`, this only applies to immutable objects. For
  instance, this function does nothing on atoms, because the metadata
  of an `atom` is part of the atom itself and can only be changed
  destructively."

  {:style/indent 1}
  [obj & metamaps]
  (try
    (apply vary-meta obj merge metamaps)
    #?(:clj (catch Exception _ obj)
       :cljs (catch js/Error _ obj))))

(defn clojure-form-source-hash

  "Hash a clojure form string into a 32 bit num.
  Meant to be called with printed representations of a form,
  or a form source read from a file."

  [s]
  (let [M 4294967291
        clean-s (-> s
                    (str/replace #"#[/.a-zA-Z0-9_-]+" "") ;; remove tags
                    (str/replace #"\^:[a-zA-Z0-9_-]+" "") ;; remove meta keys
                    (str/replace #"\^\{.+?\}" "")         ;; remove meta maps
                    (str/replace #";.+\n" "")             ;; remove comments
                    (str/replace #"[ \t\n]+" ""))         ;; remove non visible
        ] 
    (loop [sum 0
           mul 1
           i 0
           [c & srest] clean-s]
      (if (nil? c)
        (mod sum M)
        (let [mul' (if (= 0 (mod i 4)) 1 (* mul 256))
              sum' (+ sum (* (int c) mul'))]
          (recur sum' mul' (inc i) srest))))))

(defn- obj-coord [kind obj]
  (str kind (clojure-form-source-hash (pr-str obj))))

(defn walk-code-form

  "Walk through form calling (f coor element).
  The value of coor is a vector of indices representing element's
  position in the form or a string for navigating into maps and set which
  are unordered. In the case of map elements, the string will start with a K or a V
  depending if it is a key or a value and will be followed by the hash of the key form for the entry.
  For sets it will always start with K followed by the hash of the element form.
  All metadata of objects in the form is preserved."
  
  ([f form] (walk-code-form [] f form))
  ([coord f form]
   (let [walk-sequential (fn [forms]
                           (->> forms
                                (map-indexed (fn [idx frm]
                                               (walk-code-form (conj coord idx) f frm)))))
         walk-set (fn [forms]
                    (->> forms
                         (map (fn [frm]                                
                                (walk-code-form (conj coord (obj-coord "K" frm)) f frm)))
                         (into #{})))
         walk-map (fn [m]
                    (reduce-kv (fn [r kform vform]
                                 (assoc r
                                        (walk-code-form (conj coord (obj-coord "K" kform)) f kform)
                                        (walk-code-form (conj coord (obj-coord "V" kform)) f vform)))
                               (empty m)
                               m))
         
         result (cond
                  
                  (and (map? form) (not (record? form))) (walk-map form)                  
                  (set? form)                            (walk-set form)
                  (list? form)                           (apply list (walk-sequential form))
                  (seq? form)                            (doall (walk-sequential form))
                  (coll? form)                           (into (empty form) (walk-sequential form))                                    
                  :else form)]
     
     (f coord (merge-meta result (meta form))))))

(defn tag-form-recursively
  "Recursively add coordinates to all forms"
  [form key]  
  (walk-code-form (fn [coor frm]
                    (if (or (symbol? frm)
                            (seq? frm))
                      (merge-meta frm {key coor})
                      frm))
                  form))

#?(:clj
   (defn colored-string [s c]
     (let [color {:red 31
                  :yellow 33}]
       (format "\033[%d;1;1m%s\033[0m" (color c) s)))

   :cljs
   (defn colored-string [_ _]
     "UNIMPLEMENTED"))

#?(:clj
   (defmacro lazy-binding
     "Like clojure.core/binding but instead of a vec of vars it accepts a vec
  of symbols, and will resolve the vars with requiring-resolve"
     [bindings & body]     
     (let [vars-binds (mapcat (fn [[var-symb var-val]]
                                [`(clojure.core/requiring-resolve '~var-symb) var-val])
                              (partition 2 bindings))]
       `(let []
          (push-thread-bindings (hash-map ~@vars-binds))
          (try
            ~@body
            (finally
              (pop-thread-bindings)))))))

#?(:clj
   (defn println-err [& args]
     (binding [*out* *err*]
       (apply println args))))

#?(:clj (def out-print-writer *out*))

#?(:clj
   (defn log [& msgs]
     (binding [*out* out-print-writer]
       (apply println msgs)))
   :cljs
   (defn log [& msgs]
     (apply js/console.log msgs)))

#?(:clj
   (defn log-error
     ([msg] (binding [*out* *err*]
              (println msg)))
     ([msg e]
      (binding [*out* *err*]
        (println msg)
        (.printStackTrace e))))
   :cljs
   (defn log-error
     ([msg] (js/console.error msg))
     ([msg e]
      (js/console.error msg e))))

