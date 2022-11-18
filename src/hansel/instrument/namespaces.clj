(ns hansel.instrument.namespaces
  (:require [hansel.instrument.utils :as inst-utils]
            [hansel.instrument.forms :as inst-forms]
            [hansel.utils :as utils]
            [clojure.string :as str]
            [clojure.tools.namespace.parse :as tools-ns-parse]
            [clojure.tools.namespace.file :as tools-ns-file]
            [clojure.tools.namespace.dependency :as tools-ns-deps]))

(def clj-namespaces-config
  {:get-all-ns-fn inst-utils/get-all-ns-fn-clj
   :eval-in-ns-fn inst-utils/eval-in-ns-fn-clj
   :file-forms-fn inst-utils/file-forms-fn-clj
   :files-for-ns-fn inst-utils/files-for-ns-fn-clj
   :compiler :clj})

(def shadow-cljs-namespaces-config
  {:get-all-ns-fn inst-utils/get-all-ns-fn-cljs
   :eval-in-ns-fn inst-utils/eval-in-ns-fn-cljs
   :file-forms-fn inst-utils/file-forms-fn-cljs
   :files-for-ns-fn inst-utils/files-for-ns-fn-cljs
   :compiler :cljs})

(def flow-storm-ns-tag "FLOWNS")

(defn all-namespaces

  "Return all loaded namespaces that match with `ns-strs` but
  excluding `excluding-ns`. If `prefixes?` is true, `ns-strs`
  will be used as prefixes, else a exact match will be required."

  [ns-strs {:keys [excluding-ns prefixes? get-all-ns-fn] :as config}]
  (->> (get-all-ns-fn config)
       (keep (fn [ns]
               (let [nsname (str ns)]
                 (when (and (not (excluding-ns nsname))
                            (not (str/includes? nsname flow-storm-ns-tag))
                            (some (fn [ns-str]
                                    (if prefixes?
                                      (str/starts-with? nsname ns-str)
                                      (= nsname ns-str)))
                                  ns-strs))
                   ns))))
       doall))

(defn ns-vars

  "Return all vars for a `ns`."

  [ns]
  (vals (ns-interns ns)))

(defn ns-vars-cljs

  "Return all vars for a ClojureScript `ns`."

  [ns-symb]
  (let [ns-interns (requiring-resolve 'cljs.analyzer.api/ns-interns)]
    (->> (ns-interns ns-symb)
         keys)))

(defn read-file-ns-decl

  "Attempts to read a (ns ...) declaration from `file` and returns the unevaluated form.

  Returns nil if ns declaration cannot be found.

  `read-opts` is passed through to tools.reader/read."

  [file]
  (tools-ns-file/read-file-ns-decl file))

(defn interesting-form?

  "Predicate to check if a `form` is interesting to instrument."

  [form _]

  (and (seq? form)
       (when (symbol? (first form))
         (not (#{"ns" "comment" "defprotocol"} (-> form first name))))))

(defn eval-form-error-data [ex]
  (let [e-msg (.getMessage ex)]
    (cond

      ;; known issue, using recur inside fn* (without loop*)
      (str/includes? e-msg "recur")
      {:type :known-error
       :msg "We can't yet instrument using recur inside fn* (without loop*)"}

      (and (.getCause ex) (.getMessage (.getCause ex)) (str/includes? (.getMessage (.getCause ex)) "Must assign primitive to primitive mutable"))
      {:type :known-error
       :msg "Instrumenting (set! x ...) inside a deftype* being x a mutable primitive type confuses the compiler"
       :retry-disabling #{:trace-expr-exec}}

      (and (.getCause ex) (.getMessage (.getCause ex)) (str/includes? (.getMessage (.getCause ex)) "Method code too large!"))
      {:type :known-error
       :msg "Instrumented expression is too large for the clojure compiler"
       :retry-disabling #{:trace-expr-exec}}

      :else
      (binding [*print-meta* true]
        #_(println (format "Evaluating form %s Msg: %s Cause : %s" (pr-str inst-form) (.getMessage ex) (.getMessage (.getCause ex))) ex)
        #_(System/exit 1)
        {:type :unknown-error :msg e-msg}))))

(defn re-eval-form

  "Re evaluate `form` under namespace `ns-symb`, posibliy instrumenting it before when `uninstrument?` is false,
  in which case it returns the set of instrumented fns."

  ([ns-symb form config] (re-eval-form ns-symb form config false))

  ([ns-symb form {:keys [compiler uninstrument? eval-in-ns-fn] :as config} retrying?]

   (let [inst-opts (select-keys config [:disable :excluding-ns :excluding-fn :verbose?
                                        :trace-form-init :trace-fn-call :trace-fn-return :trace-expr-exec :trace-bind])]

     (try
       (if uninstrument?

         (do
           (eval-in-ns-fn ns-symb form config)
           #{})

         (let [{:keys [init-forms inst-form instrumented-fns]}
               #_:clj-kondo/ignore
               (binding [*ns* (find-ns ns-symb)] ;; bind ns for clojure
                 (utils/lazy-binding [cljs.analyzer/*cljs-ns* ns-symb] ;; bind ns for clojurescript
                                     (inst-forms/instrument inst-opts form)))]
           (case compiler

             ;; for ClojureScript we are instrumenting the form twice,
             ;; once for getting the `instrumented-fns` and the second because we have
             ;; to eval (instrument ... form), since we can't eval `inst-form` because
             ;; (defrecord ...) and multy-arity defn macroexpansions can't be evaluated
             :cljs (let [to-eval-form `(hansel.api/instrument ~inst-opts ~form)]
                     (eval-in-ns-fn ns-symb to-eval-form config))

             :clj (let [to-eval-form `(do
                                        ~@init-forms
                                        ~inst-form)]
                    (eval-in-ns-fn ns-symb to-eval-form config)))
           instrumented-fns))

       (catch Exception e

         (let [{:keys [msg retry-disabling] :as error-data} (eval-form-error-data e)]
           (if (and (not retrying?) retry-disabling)
             (do
               (when (:verbose? config)
                 (println (utils/colored-string (format "\n\nKnown error %s, retrying disabling %s for this form\n\n" msg retry-disabling)
                                                :yellow)))
               (re-eval-form ns-symb form (apply dissoc config retry-disabling) true))
             (throw (ex-info "Error evaluating form" (assoc error-data
                                                            :original-form form))))))))))

(defn re-eval-file-forms

  "Re evaluates all forms inside `file-url` under namespace `ns-symb` possibliy
  instrumenting them depending on the value of `uninstrument?`.

  Returns a set of instrumented fns."

  [ns-symb file-url {:keys [compiler uninstrument? file-forms-fn verbose?] :as config}]

  (let [file-forms (file-forms-fn ns-symb file-url config)]

    (println (format "\n%s namespace: %s Forms (%d) (%s)"
                     (if uninstrument? "Uninstrumenting" "Instrumenting")
                     ns-symb
                     (count file-forms)
                     (.getFile file-url)))

    ;; for Clojure save all vars meta so we can restore it after
    (let [ns-vars-meta (when (= :clj compiler)
                         (->> (vals (ns-interns (find-ns ns-symb)))
                              (reduce (fn [r v]
                                        (assoc r v (meta v)))
                                      {})))
          re-eval-form-step (fn [inst-fns form]
                              (try

                                (if-not (interesting-form? form config)

                                  (do
                                    (print ".")
                                    inst-fns)

                                  (do
                                    (print "I")
                                    (into inst-fns (re-eval-form ns-symb form config))))

                                (catch clojure.lang.ExceptionInfo ei
                                  (let [e-data (ex-data ei)
                                        ex-type (:type e-data)
                                        ex-type-color (if (= :known-error ex-type)
                                                        :yellow
                                                        :red)]
                                    (if verbose?
                                      (do
                                        (println)
                                        (print (utils/colored-string (str (ex-message ei) " " e-data) ex-type-color))
                                        (println))

                                      ;; else, quiet mode
                                      (print (utils/colored-string "X" ex-type-color)))
                                    inst-fns))))
          instrumented-fns (reduce re-eval-form-step #{} file-forms)]

      ;; for Clojure restore all var meta for the ns
      (when (= :clj compiler)
        (doseq [[v vmeta] ns-vars-meta]
          (alter-meta! v (constantly vmeta))))

      (println)
      instrumented-fns)))

(defn instrument-files-for-namespaces

  "Instrument and evaluates all forms of all loaded namespaces matching
  `ns-strs`.
  If `prefixes?` is true, `ns-strs` will be used as prefixes, else a exact match will be required.

  Returns the set of instrumented fns."

  [ns-strs {:keys [prefixes? files-for-ns-fn uninstrument?] :as config}]

  (let [{:keys [excluding-ns] :as config} (-> config
                                              (update :excluding-ns #(or % #{}))
                                              (update :disable #(or % #{})))
        ns-pred (fn [nsname]
                  (and (not (excluding-ns nsname))
                       (some (fn [ns-str]
                               (if prefixes?
                                 (str/starts-with? nsname ns-str)
                                 (= nsname ns-str)))
                             ns-strs)))

        ;; first filter all the known namespaces with the
        ;; required namespaces predicate
        ns-set (->> (all-namespaces ns-strs config)
                    (filter #(ns-pred (str %)))
                    (into #{}))

        ;; a namespace could be defined in multiple files (like the clojure.pprint)
        ;; so try to grab all the files related to the required `ns-set`.
        ;; We also grab each file dependencies since we want to process them in
        ;; topological order
        namespaces-files-set (->> ns-set
                                  (mapcat
                                   (fn [ns-symb]
                                     (->> (files-for-ns-fn ns-symb config)
                                          (into #{})
                                          (map (fn [file-url]
                                                 (let [ns-decl-form (read-file-ns-decl file-url)
                                                       deps (tools-ns-parse/deps-from-ns-decl ns-decl-form)]
                                                   {:ns ns-symb
                                                    :file file-url
                                                    :deps (filter ns-pred deps)}))))))
                                  (into #{}))

        _ (println (format "Found %d namespaces matching the predicates, which leads to %d files that needs to be %s"
                           (count ns-set)
                           (count namespaces-files-set)
                           (if uninstrument? "uninstrumented" "instrumented")))

        ;; build the namespace dependency graph so we can
        ;; sort them in topological order.
        ;; namespaces with no dependencies will not be added to the graph
        ;; since there is no way to do it
        ns-graph (reduce (fn [g {:keys [ns deps]}]
                           (reduce (fn [gg dep-ns-name]
                                     (tools-ns-deps/depend gg ns dep-ns-name))
                                   g
                                   deps))
                         (tools-ns-deps/graph)
                         namespaces-files-set)

        ns-symb->ns (group-by :ns namespaces-files-set)

        ;; all files that have dependencies between eachother that need to be
        ;; processed in topological order
        topo-sorted-files (->> (tools-ns-deps/topo-sort ns-graph)
                               (mapcat (fn [ns-symb] (get ns-symb->ns ns-symb)))
                               (keep (fn [{:keys [file] :as ns-info}]
                                       (when file
                                         ns-info)))
                               doall)

        independent-files (filter #(empty? (:deps %)) namespaces-files-set)

        files-to-be-instrumented (into topo-sorted-files independent-files)

        affected-namespaces (into #{} (map :ns files-to-be-instrumented))

        inst-fns (reduce (fn [ifns {:keys [ns file]}]
                           (into ifns (re-eval-file-forms ns file config)))
                         #{}
                         files-to-be-instrumented)]
    {:inst-fns inst-fns
     :affected-namespaces affected-namespaces}))
