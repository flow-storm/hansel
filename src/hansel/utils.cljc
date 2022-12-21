(ns hansel.utils
  #?(:clj (:refer-clojure :exclude [format]))
  #?(:cljs (:require [goog.string :as gstr]
                     [goog.string.format])))

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

(defn walk-indexed
  "Walk through form calling (f coor element).
  The value of coor is a vector of indices representing element's
  address in the form. Unlike `clojure.walk/walk`, all metadata of
  objects in the form is preserved."
  ([f form] (walk-indexed [] f form))
  ([coor f form]
   (let [map-inner (fn [forms]
                     (map-indexed #(walk-indexed (conj coor %1) f %2)
                                  forms))         
         walk-indexed-map (fn [map]
                            (map-indexed (fn [i [k v]]
                                           [(walk-indexed (conj coor (* 2 i)) f k)
                                            (walk-indexed (conj coor (inc (* 2 i))) f v)])
                                         map))
         result (cond
                  
                  ;; Clojure uses array-maps up to some map size (8 currently), which are sorted.
                  ;; So for small maps we take advantage of that.
                  ;; We will not be able to walk with coordinates over maps with more than 8
                  ;; keys since they are unordered. We have the same issue
                  ;; as with sets here.
                  ;; If for is a record we also skip instrumentation for now
                  (map? form) (if (and (<= (count form) 8)
                                       (not (record? form)))
                                (into {} (walk-indexed-map form))
                                form)
                  ;; Order of sets is unpredictable, unfortunately, so we don't know the coords
                  (set? form)  form
                  ;; Borrowed from clojure.walk/walk
                  (list? form) (apply list (map-inner form))
                  (map-entry? form) (vec (map-inner form))
                  (seq? form)  (doall (map-inner form))
                  (coll? form) (into (empty form) (map-inner form))
                  :else form)]
     (f coor (merge-meta result (meta form))))))

(defn tag-form-recursively
  "Recursively add coordinates to all forms"
  [form key]
  ;; Don't use `postwalk` because it destroys previous metadata.
  (walk-indexed (fn [coor frm]
                  (merge-meta frm {key coor}))
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

