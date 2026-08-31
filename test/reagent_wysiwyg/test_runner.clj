(ns reagent-wysiwyg.test-runner
  (:require [clojure.test :as test]
            reagent-wysiwyg.hiccup-test
            reagent-wysiwyg.model-test
            reagent-wysiwyg.preview-test
            reagent-wysiwyg.source-test
            reagent-wysiwyg.state-test
            reagent-wysiwyg.view-test))

(def test-namespaces
  '[reagent-wysiwyg.hiccup-test
    reagent-wysiwyg.model-test
    reagent-wysiwyg.preview-test
    reagent-wysiwyg.source-test
    reagent-wysiwyg.state-test
    reagent-wysiwyg.view-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
