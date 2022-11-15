(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def class-dir "target/classes")

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [lib 'com.github.jpmonettas/hansel
        version (format "0.1.%s" (b/git-count-revs nil))
        basis (b/create-basis {:project "deps.edn"
                               :aliases []})
        jar-file (format "target/%s.jar" (name lib))
        src-dirs ["src"]]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs src-dirs})
    (b/copy-dir {:src-dirs src-dirs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file jar-file})))
