(ns reagent-wysiwyg.hiccup-test
  (:require [clojure.test :refer [deftest is testing]]
            [reagent-wysiwyg.hiccup :as hiccup]))

(deftest supported-form-round-trip
  (let [form [:section {:id "hero" :class "wide" :style {:color "red"}}
              [:h1 "Hello"]
              [:input {:type "checkbox" :checked true}]
              42]
        root (hiccup/form->node form)]
    (is (= form (hiccup/node->form root)))
    (is (= form (hiccup/node->form (hiccup/parse-string (hiccup/canonical-string root)))))))

(deftest rejects-executable-and-unsupported-forms
  (doseq [form ['[custom-component {:x 1}]
                '[:script "alert(1)"]
                '[:div (str "runtime")]
                '[:button {:on-click (fn [] :x)} "Run"]
                '[:img "child"]]]
    (is (thrown? clojure.lang.ExceptionInfo (hiccup/form->node form)) (pr-str form))))

(deftest parse-errors-have-useful-paths
  (try
    (hiccup/parse-string "[:div [:unknown \"x\"]]")
    (is false "Expected validation failure")
    (catch clojure.lang.ExceptionInfo error
      (is (= [1 0] (:path (ex-data error))))
      (is (re-find #"Unsupported element" (hiccup/error-message error)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                        (hiccup/parse-string "[:p \"a\"] [:p \"b\"]"))))
