(ns hansel.instrument.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hansel.utils :as utils])
  (:import [java.io StringReader]))

(defn get-all-ns-fn-clj [_] (map ns-name (all-ns)))

(defn get-all-ns-fn-cljs  [{:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)]
    (-> (compiler-env build-id) :cljs.analyzer/namespaces keys)))

(defn eval-in-ns-fn-clj [ns-symb form _]
  (binding [*ns* (find-ns ns-symb)]
    (eval form)))

(defn eval-in-ns-fn-cljs  [ns-symb form {:keys [build-id]}]
  (let [cljs-eval (requiring-resolve 'shadow.cljs.devtools.api/cljs-eval)
        {:keys [results err]} (cljs-eval build-id (pr-str form) {:ns ns-symb})]
    (if-not (str/blank? err)
      (throw (Exception. err))
      (first results))))

(defn file-forms-fn-clj [ns-symb file-url _]
  (binding [*ns* (find-ns ns-symb)]
    (->> (format "[%s]" (slurp file-url))
        (read-string {:read-cond :allow}))))

(defn file-forms-fn-cljs  [ns-symb file-url {:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        forms-seq (requiring-resolve 'cljs.analyzer.api/forms-seq)
        cenv (atom (compiler-env build-id))]
    (try
      #_:clj-kondo/ignore
      (utils/lazy-binding
       [cljs.analyzer/*cljs-ns* ns-symb
        cljs.env/*compiler* cenv]
       (let [file-str (slurp file-url)]
         (doall (forms-seq (StringReader. file-str)))))
      (catch Exception e
        (binding [*out* *err*]
          (println "Error reading forms for " file-url)
          (.printStackTrace e))))))

(defn files-for-ns-fn-clj [ns-symb _]
  (let [ns-vars (vals (ns-interns (find-ns ns-symb)))]
    (->> ns-vars
         (keep (fn [v]
                 (let [file-name (:file (meta v))
                       file (when file-name
                              (or (io/resource file-name)
                                  (.toURL (io/file file-name))))]
                   file)))
         (into #{}))))

(defn files-for-ns-fn-cljs  [ns-symb {:keys [build-id]}]
  (let [compiler-env (requiring-resolve 'shadow.cljs.devtools.api/compiler-env)
        ns (-> (compiler-env build-id) :cljs.analyzer/namespaces ns-symb)
        file-name (-> ns :meta :file)
        file (when file-name
               (or (io/resource file-name)
                   (.toURL (io/file file-name))))]
    (when file
      [file])))
