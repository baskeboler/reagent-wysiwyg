(ns reagent-wysiwyg.preview-test
  (:require [clojure.test :refer [deftest is]]
            [reagent-wysiwyg.hiccup :as hiccup]
            [reagent-wysiwyg.preview :as preview]))

(deftest escapes-text-and-attributes
  (let [root (hiccup/form->node [:div {:title "a\"<&"}
                                  "<script>alert('x')</script>"])
        html (preview/node-html root nil)]
    (is (re-find #"a&amp;quot;" (clojure.string/replace html "&quot;" "&amp;quot;")))
    (is (re-find #"&lt;script&gt;" html))
    (is (not (re-find #"<script>alert" html)))))

(deftest html-has-editor-contract
  (let [root (hiccup/form->node [:button {:class "primary" :style {:color "red"}} "Go"])
        html (preview/document-html root (:id root))]
    (is (re-find #"data-editor-id" html))
    (is (re-find #"data-editor-selected=\"true\"" html))
    (is (re-find #"window.editorBridge.move" html))
    (is (re-find #"color:red" html))))
