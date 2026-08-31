(ns reagent-wysiwyg.state
  (:require [clojure.string :as str]
            [reagent-wysiwyg.hiccup :as hiccup]
            [reagent-wysiwyg.model :as model]))

(def history-limit 100)

(defn initial-state []
  (let [root (model/default-root)
        source (hiccup/canonical-string root)]
    {:root root
     :selected-id (:id root)
     :source-draft source
     :source-error nil
     :source-dirty? false
     :dirty? false
     :history []
     :future []
     :file nil
     :file-source nil
     :file-digest nil
     :component {:name 'generated-view
                 :namespace 'app.ui}
     :export-namespace "app.ui"
     :export-name "generated-view"
     :status "Ready"}))

(defn snapshot [state]
  (select-keys state [:root :selected-id]))

(defn sync-source [state]
  (assoc state
         :source-draft (hiccup/canonical-string (:root state))
         :source-error nil
         :source-dirty? false))

(defn commit-root [state root selected-id status]
  (if (= root (:root state))
    state
    (-> state
        (update :history #(->> (conj % (snapshot state)) (take-last history-limit) vec))
        (assoc :future []
               :root root
               :selected-id (if (model/find-node root selected-id)
                              selected-id
                              (:id root))
               :dirty? true
               :status status)
        sync-source)))

(defn select [state id]
  (if (model/find-node (:root state) id)
    (assoc state :selected-id id)
    state))

(defn insert [state tag attrs target-id position]
  (let [node (model/palette-node tag attrs)
        root (model/insert-node (:root state) target-id position node)]
    (commit-root state root (:id node) (str "Added <" (name tag) ">"))))

(defn move [state source-id target-id position]
  (commit-root state
               (model/move-node (:root state) source-id target-id position)
               source-id
               "Moved component"))

(defn delete-selected [state]
  (let [id (:selected-id state)
        parent (model/parent-of (:root state) id)
        root (model/remove-node (:root state) id)]
    (if root
      (commit-root state root (or (:id parent) (:id root)) "Deleted component")
      (assoc state :status "The root component cannot be deleted"))))

(defn duplicate-selected [state]
  (let [root (model/duplicate-node (:root state) (:selected-id state))]
    (commit-root state root (:selected-id state) "Duplicated component")))

(defn move-selected [state direction]
  (commit-root state
               (model/move-sibling (:root state) (:selected-id state) direction)
               (:selected-id state)
               "Reordered component"))

(defn change-attr [state key value]
  (commit-root state
               (model/set-attr (:root state) (:selected-id state) key value)
               (:selected-id state)
               (str "Updated " key)))

(defn remove-attr [state key]
  (commit-root state
               (model/remove-attr (:root state) (:selected-id state) key)
               (:selected-id state)
               (str "Removed " key)))

(defn change-style [state key value]
  (commit-root state
               (model/set-style (:root state) (:selected-id state) key value)
               (:selected-id state)
               (str "Updated style " key)))

(defn remove-style [state key]
  (commit-root state
               (model/remove-style (:root state) (:selected-id state) key)
               (:selected-id state)
               (str "Removed style " key)))

(defn change-text [state value]
  (commit-root state
               (model/set-text (:root state) (:selected-id state) value)
               (:selected-id state)
               "Updated text"))

(defn edit-source [state source]
  (if (= source (:source-draft state))
    state
    (assoc state :source-draft source :source-dirty? true :source-error nil)))

(defn apply-source [state]
  (try
    (let [root (hiccup/parse-string (:source-draft state))]
      (-> (commit-root state root (:id root) "Applied source")
          (assoc :source-dirty? false :source-error nil)))
    (catch Throwable error
      (assoc state :source-error (hiccup/error-message error)
             :status "Source contains errors"))))

(defn undo [state]
  (if-let [previous (peek (:history state))]
    (-> state
        (assoc :root (:root previous)
               :selected-id (:selected-id previous)
               :history (pop (:history state))
               :future (conj (:future state) (snapshot state))
               :dirty? true
               :status "Undid edit")
        sync-source)
    state))

(defn redo [state]
  (if-let [next-state (peek (:future state))]
    (-> state
        (assoc :root (:root next-state)
               :selected-id (:selected-id next-state)
               :future (pop (:future state))
               :history (conj (:history state) (snapshot state))
               :dirty? true
               :status "Redid edit")
        sync-source)
    state))

(defn load-component [state document component]
  (let [root (:root component)]
    (assoc (initial-state)
           :root root
           :selected-id (:id root)
           :source-draft (hiccup/canonical-string root)
           :file (:file document)
           :file-source (:source document)
           :file-digest (:digest document)
           :component (select-keys component [:name :namespace :top-index :body-sexpr-index])
           :export-namespace (str (or (:namespace component) 'app.ui))
           :export-name (str (:name component))
           :status (str "Opened " (.getName ^java.io.File (:file document))))))

(defn mark-saved [state file source digest component]
  (assoc state
         :file file
         :file-source source
         :file-digest digest
         :component component
         :dirty? false
         :source-dirty? false
         :source-error nil
         :status (str "Saved " (.getName ^java.io.File file))))
