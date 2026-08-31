(ns reagent-wysiwyg.model
  (:require [clojure.string :as str])
  (:import [java.util UUID]))

(def palette-groups
  [{:name "Layout"
    :items [{:label "Container" :tag :div}
            {:label "Section" :tag :section}
            {:label "Header" :tag :header}
            {:label "Main" :tag :main}
            {:label "Footer" :tag :footer}
            {:label "Navigation" :tag :nav}
            {:label "Article" :tag :article}
            {:label "Aside" :tag :aside}]}
   {:name "Text"
    :items [{:label "Heading 1" :tag :h1}
            {:label "Heading 2" :tag :h2}
            {:label "Heading 3" :tag :h3}
            {:label "Paragraph" :tag :p}
            {:label "Span" :tag :span}
            {:label "Strong" :tag :strong}
            {:label "Emphasis" :tag :em}]}
   {:name "Lists"
    :items [{:label "Bulleted list" :tag :ul}
            {:label "Numbered list" :tag :ol}
            {:label "List item" :tag :li}]}
   {:name "Media & actions"
    :items [{:label "Link" :tag :a}
            {:label "Image" :tag :img}
            {:label "Button" :tag :button}]}
   {:name "Forms"
    :items [{:label "Form" :tag :form}
            {:label "Label" :tag :label}
            {:label "Input" :tag :input}
            {:label "Text area" :tag :textarea}
            {:label "Select" :tag :select}
            {:label "Option" :tag :option}
            {:label "Checkbox" :tag :input :attrs {:type "checkbox"}}
            {:label "Radio" :tag :input :attrs {:type "radio"}}]}])

(def supported-tags
  (into #{} (map :tag) (mapcat :items palette-groups)))

(def void-tags #{:img :input})
(def reserved-attrs #{:data-editor-id :draggable :onclick :ondragstart :ondragover :ondrop})

(defn new-id [] (str (UUID/randomUUID)))

(defn text-node [value]
  {:id (new-id) :kind :text :value value})

(defn element-node
  ([tag] (element-node tag {} []))
  ([tag attrs children]
   {:id (new-id)
    :kind :element
    :tag tag
    :attrs (or attrs {})
    :children (vec children)}))

(def starter-text
  {:h1 "Heading"
   :h2 "Heading"
   :h3 "Heading"
   :p "Paragraph text"
   :span "Text"
   :strong "Important text"
   :em "Emphasized text"
   :li "List item"
   :a "Link"
   :button "Button"
   :label "Label"
   :textarea ""
   :option "Option"})

(defn palette-node
  ([tag] (palette-node tag {}))
  ([tag attrs]
   (let [defaults (case tag
                    :a {:href "#"}
                    :img {:src "" :alt "Image"}
                    :input {:type "text" :placeholder "Input"}
                    {})
         text (get starter-text tag)]
     (element-node tag
                   (merge defaults attrs)
                   (cond-> [] (some? text) (conj (text-node text)))))))

(defn default-root []
  (element-node :div {:class "container"}
                [(element-node :h1 {} [(text-node "New interface")])
                 (element-node :p {} [(text-node "Drag components here to begin.")])]))

(defn element? [node] (= :element (:kind node)))
(defn text? [node] (= :text (:kind node)))
(defn container? [node]
  (and (element? node) (not (contains? void-tags (:tag node)))))

(defn find-node [root id]
  (when root
    (if (= id (:id root))
      root
      (some #(find-node % id) (:children root)))))

(defn contains-id? [root id] (boolean (find-node root id)))

(defn update-node [root id f]
  (cond
    (= id (:id root)) (f root)
    (element? root) (update root :children
                            (fn [children]
                              (mapv #(update-node % id f) children)))
    :else root))

(defn parent-of [root id]
  (when (element? root)
    (if (some #(= id (:id %)) (:children root))
      root
      (some #(parent-of % id) (:children root)))))

(defn remove-node [root id]
  (when-not (= id (:id root))
    (letfn [(remove* [node]
              (if (element? node)
                (update node :children
                        (fn [children]
                          (->> children
                               (remove #(= id (:id %)))
                               (mapv remove*))))
                node))]
      (remove* root))))

(defn child-index [parent id]
  (first (keep-indexed #(when (= id (:id %2)) %1) (:children parent))))

(defn deletable? [root id]
  (and (some? (find-node root id))
       (not= id (:id root))))

(defn can-move-sibling? [root id direction]
  (when-let [parent (parent-of root id)]
    (let [index (child-index parent id)
          destination (+ index direction)]
      (< -1 destination (count (:children parent))))))

(defn insert-node
  "Insert node relative to target-id. Position is :inside, :before or :after."
  [root target-id position node]
  (let [target (find-node root target-id)]
    (cond
      (nil? target) root
      (= position :inside)
      (if (container? target)
        (update-node root target-id #(update % :children conj node))
        root)

      (= target-id (:id root)) root

      :else
      (let [parent (parent-of root target-id)
            index (child-index parent target-id)
            insertion-index (if (= position :after) (inc index) index)]
        (update-node root (:id parent)
                     #(update % :children
                              (fn [children]
                                (vec (concat (subvec children 0 insertion-index)
                                             [node]
                                             (subvec children insertion-index))))))))))

(defn move-node [root source-id target-id position]
  (let [source (find-node root source-id)
        target (find-node root target-id)]
    (if (or (nil? source)
            (nil? target)
            (= source-id (:id root))
            (= source-id target-id)
            (contains-id? source target-id)
            (and (= position :inside) (not (container? target))))
      root
      (let [without (remove-node root source-id)]
        (insert-node without target-id position source)))))

(defn duplicate-node [root id]
  (if-let [node (find-node root id)]
    (if (= id (:id root))
      root
      (letfn [(fresh [n]
                (cond-> (assoc n :id (new-id))
                  (element? n) (update :children #(mapv fresh %))))]
        (insert-node root id :after (fresh node))))
    root))

(defn move-sibling [root id direction]
  (if-let [parent (parent-of root id)]
    (let [index (child-index parent id)
          other (+ index direction)]
      (if (< -1 other (count (:children parent)))
        (update-node root (:id parent)
                     #(update % :children
                              (fn [children]
                                (assoc children
                                       index (nth children other)
                                       other (nth children index)))))
        root))
    root))

(defn set-attr [root id key value]
  (update-node root id
               (fn [node]
                 (if (element? node)
                   (if (or (nil? value) (and (string? value) (str/blank? value)))
                     (update node :attrs dissoc key)
                     (assoc-in node [:attrs key] value))
                   node))))

(defn remove-attr [root id key]
  (update-node root id #(if (element? %) (update % :attrs dissoc key) %)))

(defn set-style [root id key value]
  (update-node root id
               (fn [node]
                 (if (element? node)
                   (let [styles (get-in node [:attrs :style] {})
                         styles' (if (str/blank? (str value))
                                   (dissoc styles key)
                                   (assoc styles key value))]
                     (if (empty? styles')
                       (update node :attrs dissoc :style)
                       (assoc-in node [:attrs :style] styles')))
                   node))))

(defn remove-style [root id key]
  (set-style root id key ""))

(defn set-text [root id value]
  (update-node root id #(if (text? %) (assoc % :value value) %)))

(defn outline [root]
  (letfn [(walk [node depth]
            (cons {:id (:id node)
                   :depth depth
                   :label (if (element? node)
                            (str "<" (name (:tag node)) ">")
                            (let [text (str (:value node))]
                              (str "\"" (subs text 0 (min 28 (count text))) "\"")))}
                  (mapcat #(walk % (inc depth)) (:children node))))]
    (vec (walk root 0))))
