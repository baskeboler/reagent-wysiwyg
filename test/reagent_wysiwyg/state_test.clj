(ns reagent-wysiwyg.state-test
  (:require [clojure.test :refer [deftest is]]
            [reagent-wysiwyg.hiccup :as hiccup]
            [reagent-wysiwyg.model :as model]
            [reagent-wysiwyg.state :as state]))

(deftest visual-edits-synchronize-source-and-history
  (let [initial (state/initial-state)
        edited (state/insert initial :button {} (:selected-id initial) :inside)]
    (is (:dirty? edited))
    (is (false? (:source-dirty? edited)))
    (is (re-find #":button" (:source-draft edited)))
    (is (= 1 (count (:history edited))))
    (is (= (:root initial) (:root (state/undo edited))))
    (is (= (:root edited) (:root (state/redo (state/undo edited)))))))

(deftest invalid-source-does-not-change-model
  (let [initial (state/initial-state)
        result (-> initial
                   (state/edit-source "[:script \"bad\"]")
                   state/apply-source)]
    (is (= (:root initial) (:root result)))
    (is (string? (:source-error result)))
    (is (:source-dirty? result))))

(deftest valid-source-applies-atomically
  (let [result (-> (state/initial-state)
                   (state/edit-source "[:main {:class \"app\"} [:h1 \"Hello\"]]")
                   state/apply-source)]
    (is (= :main (get-in result [:root :tag])))
    (is (= "app" (get-in result [:root :attrs :class])))
    (is (nil? (:source-error result)))
    (is (false? (:source-dirty? result)))))

(deftest history-is-bounded
  (let [result (reduce (fn [s n]
                         (state/change-attr s :title (str n)))
                       (state/initial-state)
                       (range (+ state/history-limit 20)))]
    (is (= state/history-limit (count (:history result))))))

(deftest move-and-delete-operate-on-current-selection
  (let [first-child (model/palette-node :p)
        selected (model/palette-node :button)
        root (model/element-node :div {} [first-child selected])
        initial (assoc (state/initial-state) :root root :selected-id (:id selected))
        moved (state/move-selected initial -1)
        deleted (state/delete-selected moved)]
    (is (= [(:id selected) (:id first-child)] (mapv :id (:children (:root moved)))))
    (is (= (:id selected) (:selected-id moved)))
    (is (= [(:id first-child)] (mapv :id (:children (:root deleted)))))
    (is (= (:id first-child) (:selected-id deleted)))
    (is (= "Deleted selected component" (:status deleted)))))
