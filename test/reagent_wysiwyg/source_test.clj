(ns reagent-wysiwyg.source-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [reagent-wysiwyg.hiccup :as hiccup]
            [reagent-wysiwyg.source :as source]))

(def sample-source
  "(ns example.ui)\n\n;; preserved comment\n(defn first-view []\n  [:div {:class \"old\"} \"First\"])\n\n(defn helper [x] x)\n\n(defn second-view [] [:p \"Second\"])\n")

(deftest discovers-supported-zero-argument-components
  (let [components (source/discover-components sample-source)]
    (is (= '[first-view second-view] (mapv :name components)))
    (is (= '[example.ui example.ui] (mapv :namespace components)))))

(deftest replaces-only-selected-body
  (let [component (first (source/discover-components sample-source))
        replacement (hiccup/form->node [:section {:id "new"} [:h1 "Changed"]])
        result (source/replace-component sample-source component replacement)]
    (is (re-find #"preserved comment" result))
    (is (re-find #"\(defn helper \[x\] x\)" result))
    (is (re-find #"\(defn second-view \[\] \[:p \"Second\"\]\)" result))
    (is (re-find #"\[:section \{:id \"new\"\}" result))
    (is (= 2 (count (source/discover-components result))))))

(deftest export-and-atomic-write
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "reagent-wysiwyg-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir "component.cljs")
        root (hiccup/form->node [:div "Saved"])
        content (source/exported-source 'demo.ui 'saved-view root)]
    (try
      (source/atomic-write! file content)
      (is (= content (slurp file)))
      (is (= 'saved-view (:name (first (:components (source/read-document file))))))
      (is (false? (source/externally-modified? file (source/sha256 content))))
      (spit file (str content "\n;; external"))
      (is (true? (source/externally-modified? file (source/sha256 content))))
      (finally
        (.delete file)
        (.delete dir)))))
