(ns build
  (:require [clojure.tools.build.api :as b]))

(def default-version "0.1.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  "Remove generated build output."
  [params]
  (b/delete {:path "target"})
  params)

(defn uber
  "Build an executable, Linux-targeted uberjar. Optionally pass :version."
  [{:keys [version] :or {version default-version} :as params}]
  (let [uber-file (format "target/reagent-wysiwyg-%s-standalone.jar" version)]
    (clean params)
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir class-dir})
    (b/compile-clj {:basis @basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :java-opts ["-Dcljfx.skip-javafx-initialization=true"
                                "--enable-native-access=ALL-UNNAMED"]
                    :ns-compile '[reagent-wysiwyg.main]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis @basis
             :main 'reagent-wysiwyg.main})
    (println "Built" uber-file)
    (assoc params :uber-file uber-file)))
