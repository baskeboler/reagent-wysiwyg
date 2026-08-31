(ns reagent-wysiwyg.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [reagent-wysiwyg.model :as model]))

(deftest insert-and-find-nodes
  (let [root (model/element-node :div)
        child (model/palette-node :button)
        result (model/insert-node root (:id root) :inside child)]
    (is (= child (model/find-node result (:id child))))
    (is (= (:id root) (:id (model/parent-of result (:id child)))))))

(deftest insertion-respects-structure
  (let [root (model/element-node :div)
        image (model/palette-node :img)
        text (model/text-node "nope")
        with-image (model/insert-node root (:id root) :inside image)]
    (is (= with-image (model/insert-node with-image (:id image) :inside text)))
    (is (= root (model/insert-node root (:id root) :before image)))))

(deftest move-rejects-cycles-and-root-moves
  (let [inner (model/element-node :section)
        outer (model/element-node :div {} [inner])
        leaf (model/palette-node :p)
        root (model/insert-node outer (:id inner) :inside leaf)]
    (is (= root (model/move-node root (:id outer) (:id inner) :inside)))
    (is (= root (model/move-node root (:id inner) (:id leaf) :inside)))
    (let [moved (model/move-node root (:id leaf) (:id inner) :before)]
      (is (= (:id leaf) (:id (first (:children moved)))))
      (is (= (:id inner) (:id (second (:children moved))))))))

(deftest duplicate-and-delete
  (let [child (model/palette-node :p)
        root (model/element-node :div {} [child])
        duplicated (model/duplicate-node root (:id child))]
    (is (= 2 (count (:children duplicated))))
    (is (not= (:id (first (:children duplicated)))
              (:id (second (:children duplicated)))))
    (is (not= (get-in duplicated [:children 0 :children 0 :id])
              (get-in duplicated [:children 1 :children 0 :id])))
    (is (= 1 (count (:children (model/remove-node duplicated (:id child))))))
    (is (nil? (model/remove-node root (:id root))))))

(deftest attributes-styles-and-text
  (let [text (model/text-node "old")
        root (model/element-node :p {} [text])
        styled (-> root
                   (model/set-attr (:id root) :class "hero")
                   (model/set-style (:id root) :color "red")
                   (model/set-text (:id text) "new"))]
    (is (= "hero" (get-in styled [:attrs :class])))
    (is (= "red" (get-in styled [:attrs :style :color])))
    (is (= "new" (get-in styled [:children 0 :value])))
    (is (nil? (get-in (model/remove-style styled (:id root) :color) [:attrs :style])))))

(deftest selected-node-capabilities-reflect-root-and-sibling-edges
  (let [first-child (model/palette-node :p)
        middle-child (model/palette-node :button)
        last-child (model/palette-node :input)
        root (model/element-node :div {} [first-child middle-child last-child])]
    (is (false? (model/deletable? root (:id root))))
    (is (true? (model/deletable? root (:id middle-child))))
    (is (false? (model/can-move-sibling? root (:id first-child) -1)))
    (is (true? (model/can-move-sibling? root (:id middle-child) -1)))
    (is (true? (model/can-move-sibling? root (:id middle-child) 1)))
    (is (false? (model/can-move-sibling? root (:id last-child) 1)))))
