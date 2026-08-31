(ns reagent-wysiwyg.main
  (:gen-class)
  (:require [cljfx.api :as fx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reagent-wysiwyg.hiccup :as hiccup]
            [reagent-wysiwyg.model :as model]
            [reagent-wysiwyg.preview :as preview]
            [reagent-wysiwyg.source :as source]
            [reagent-wysiwyg.state :as state])
  (:import [java.util Base64]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections]
           [javafx.scene Node]
           [javafx.scene.control Alert Alert$AlertType ButtonBar$ButtonData ButtonType ChoiceDialog]
           [javafx.scene.input Clipboard ClipboardContent TransferMode]
           [javafx.scene.web WebView]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]
           [javafx.concurrent Worker$State]
           [netscape.javascript JSObject]))

(definterface EditorBridge
  (^void select [^String id])
  (^void insert [^String tag ^String target-id ^String position])
  (^void move [^String source-id ^String target-id ^String position]))

(defonce app-state (atom (assoc (state/initial-state)
                                :new-attr-key "title"
                                :new-attr-value ""
                                :new-style-key "color"
                                :new-style-value "")))
(defonce stage-instance (atom nil))
(defonce web-view-instance (atom nil))
(defonce renderer-instance (atom nil))

(declare dispatch!)

(defn- alert! [type title header content]
  (let [alert (Alert. type "" (make-array ButtonType 0))]
    (.setTitle alert title)
    (.setHeaderText alert header)
    (.setContentText alert content)
    (when @stage-instance (.initOwner alert @stage-instance))
    (.showAndWait alert)))

(defn- error! [title message]
  (alert! Alert$AlertType/ERROR title title (str message)))

(defn- confirm-discard! []
  (if-not (or (:dirty? @app-state) (:source-dirty? @app-state))
    true
    (let [result (alert! Alert$AlertType/CONFIRMATION
                         "Unsaved changes"
                         "Discard unsaved changes?"
                         "Your current visual and source edits will be lost.")]
      (and (.isPresent result) (= ButtonType/OK (.get result))))))

(defn- file-chooser [title save?]
  (let [chooser (FileChooser.)
        filter (FileChooser$ExtensionFilter.
                "ClojureScript components (*.cljs, *.cljc)"
                (into-array String ["*.cljs" "*.cljc"]))]
    (.setTitle chooser title)
    (.add (.getExtensionFilters chooser) filter)
    (if save?
      (.showSaveDialog chooser @stage-instance)
      (.showOpenDialog chooser @stage-instance))))

(defn- choose-component [components]
  (cond
    (empty? components) nil
    (= 1 (count components)) (first components)
    :else
    (let [labels (mapv (comp str :name) components)
          dialog (ChoiceDialog. (first labels) (FXCollections/observableArrayList labels))]
      (.setTitle dialog "Choose component")
      (.setHeaderText dialog "This file contains multiple editable components")
      (.setContentText dialog "Component:")
      (when @stage-instance (.initOwner dialog @stage-instance))
      (let [result (.showAndWait dialog)]
        (when (.isPresent result)
          (nth components (.indexOf labels (.get result))))))))

(defn- open-file! []
  (when (confirm-discard!)
    (when-let [file (file-chooser "Open Reagent component" false)]
      (try
        (let [document (source/read-document file)]
          (if-let [component (choose-component (:components document))]
            (reset! app-state
                    (merge (state/load-component @app-state document component)
                           (select-keys @app-state [:new-attr-key :new-attr-value
                                                    :new-style-key :new-style-value])))
            (error! "No editable components"
                    "The file has no zero-argument defn whose only body is supported literal Hiccup.")))
        (catch Throwable error
          (error! "Unable to open file" (.getMessage error)))))))

(defn- ensure-applied! []
  (when (:source-dirty? @app-state)
    (swap! app-state state/apply-source))
  (nil? (:source-error @app-state)))

(defn- normalize-save-file [file]
  (let [name (.getName ^java.io.File file)]
    (if (re-find #"\.(cljs|cljc)$" name)
      file
      (io/file (.getParentFile ^java.io.File file) (str name ".cljs")))))

(defn- parse-symbol-field [label text]
  (try
    (let [trimmed (str/trim text)
          value (edn/read-string trimmed)]
      (when (or (str/blank? trimmed)
                (not (symbol? value))
                (namespace value)
                (not= trimmed (str value)))
        (throw (ex-info (str label " must be one unqualified Clojure symbol") {})))
      value)
    (catch Throwable error
      (throw (ex-info (str label " must be one unqualified Clojure symbol") {} error)))))

(defn- export-identifiers []
  [(parse-symbol-field "Namespace" (:export-namespace @app-state))
   (parse-symbol-field "Component name" (:export-name @app-state))])

(defn- write-new-document! [file]
  (let [file (normalize-save-file file)
        {:keys [root]} @app-state
        [namespace-name component-name] (export-identifiers)
        content (source/exported-source namespace-name component-name root)]
    (source/atomic-write! file content)
    (let [document (source/read-document file)
          saved-component (or (some #(when (= component-name (:name %)) %) (:components document))
                              (first (:components document)))]
      (swap! app-state state/mark-saved file content (:digest document)
             (select-keys saved-component [:name :namespace :top-index :body-sexpr-index])))))

(defn- external-change-choice []
  (let [reload (ButtonType. "Reload" ButtonBar$ButtonData/OTHER)
        save-as (ButtonType. "Save As…" ButtonBar$ButtonData/OTHER)
        cancel ButtonType/CANCEL
        alert (Alert. Alert$AlertType/CONFIRMATION "" (into-array ButtonType [reload save-as cancel]))]
    (.setTitle alert "File changed on disk")
    (.setHeaderText alert "The source file was modified by another program")
    (.setContentText alert "Reload it, save your component to another file, or cancel.")
    (when @stage-instance (.initOwner alert @stage-instance))
    (let [result (.showAndWait alert)]
      (when (.isPresent result)
        (condp = (.get result)
          reload :reload
          save-as :save-as
          :cancel)))))

(defn- reload-current! []
  (let [{:keys [file component]} @app-state
        document (source/read-document file)
        reloaded (or (some #(when (= (:name component) (:name %)) %) (:components document))
                     (choose-component (:components document)))]
    (if reloaded
      (reset! app-state
              (merge (state/load-component @app-state document reloaded)
                     (select-keys @app-state [:new-attr-key :new-attr-value
                                              :new-style-key :new-style-value])))
      (error! "Component no longer available" "The selected component is not editable in the changed file."))))

(defn- save-as! []
  (when (ensure-applied!)
    (when-let [file (file-chooser "Save component as" true)]
      (try
        (write-new-document! file)
        (catch Throwable error
          (error! "Unable to save file" (.getMessage error)))))))

(defn- save! []
  (when (ensure-applied!)
    (let [{:keys [file file-source file-digest component root]} @app-state]
      (if-not file
        (save-as!)
        (try
          (if (source/externally-modified? file file-digest)
            (case (external-change-choice)
              :reload (reload-current!)
              :save-as (save-as!)
              nil)
            (let [content (source/replace-component file-source component root)]
              (source/atomic-write! file content)
              (let [document (source/read-document file)
                    saved-component (or (some #(when (= (:name component) (:name %)) %)
                                              (:components document))
                                        component)]
                (swap! app-state state/mark-saved file content (:digest document)
                       (select-keys saved-component [:name :namespace :top-index :body-sexpr-index])))))
          (catch Throwable error
            (error! "Unable to save file" (.getMessage error))))))))

(defn- export! []
  (when (ensure-applied!)
    (when-let [file (file-chooser "Export standalone component" true)]
      (try
        (let [{:keys [root]} @app-state
              [namespace-name component-name] (export-identifiers)]
          (source/atomic-write!
           (normalize-save-file file)
           (source/exported-source namespace-name component-name root))
          (swap! app-state assoc :status (str "Exported " (.getName ^java.io.File file))))
        (catch Throwable error
          (error! "Unable to export component" (.getMessage error)))))))

(defn- copy-source! []
  (let [content (ClipboardContent.)]
    (.putString content (hiccup/canonical-string (:root @app-state)))
    (.setContent (Clipboard/getSystemClipboard) content)
    (swap! app-state assoc :status "Copied Hiccup to clipboard")))

(defn- exit! [event]
  (if (confirm-discard!)
    (Platform/exit)
    (when event (.consume event))))

(defn- parse-inspector-value [text]
  (let [trimmed (str/trim text)]
    (cond
      (= trimmed "true") true
      (= trimmed "false") false
      (= trimmed "nil") nil
      (re-matches #"[-+]?\d+(\.\d+)?" trimmed) (edn/read-string trimmed)
      (str/starts-with? trimmed ":") (let [value (edn/read-string trimmed)]
                                        (if (keyword? value) value text))
      :else text)))

(defn- valid-keyword [text]
  (let [trimmed (str/trim text)]
    (when (re-matches #"[A-Za-z_][A-Za-z0-9_.*+!?'<>=$%-]*(/[A-Za-z_][A-Za-z0-9_.*+!?'<>=$%-]*)?" trimmed)
      (keyword trimmed))))

(defn dispatch! [{:keys [event] :as data}]
  (case event
    :new (when (confirm-discard!)
           (reset! app-state (merge (state/initial-state)
                                    (select-keys @app-state [:new-attr-key :new-attr-value
                                                             :new-style-key :new-style-value]))))
    :open (open-file!)
    :save (save!)
    :save-as (save-as!)
    :export (export!)
    :copy (copy-source!)
    :exit (exit! (:fx/event data))
    :select (swap! app-state state/select (:id data))
    :insert (swap! app-state state/insert (:tag data) (:attrs data {}) (:target-id data) (:position data))
    :move (swap! app-state state/move (:source-id data) (:target-id data) (:position data))
    :delete (swap! app-state state/delete-selected)
    :duplicate (swap! app-state state/duplicate-selected)
    :move-up (swap! app-state state/move-selected -1)
    :move-down (swap! app-state state/move-selected 1)
    :undo (swap! app-state state/undo)
    :redo (swap! app-state state/redo)
    :source-edit (swap! app-state state/edit-source (:value data))
    :source-apply (swap! app-state state/apply-source)
    :text-change (swap! app-state state/change-text (:value data))
    :attr-change (swap! app-state state/change-attr (:key data) (parse-inspector-value (:value data)))
    :attr-remove (swap! app-state state/remove-attr (:key data))
    :style-change (swap! app-state state/change-style (:key data) (:value data))
    :style-remove (swap! app-state state/remove-style (:key data))
    :ui-field (swap! app-state assoc (:key data) (:value data))
    :add-attr (let [key (valid-keyword (:new-attr-key @app-state))]
                (cond
                  (nil? key) (swap! app-state assoc :status "Attribute name is not a valid keyword")
                  (contains? model/reserved-attrs key) (swap! app-state assoc :status "That attribute is reserved")
                  (= key :style) (swap! app-state assoc :status "Use the Styles section for :style")
                  :else (do (swap! app-state state/change-attr key
                                         (parse-inspector-value (:new-attr-value @app-state)))
                            (swap! app-state assoc :new-attr-value ""))))
    :add-style (let [key (valid-keyword (:new-style-key @app-state))]
                 (if key
                   (do (swap! app-state state/change-style key (:new-style-value @app-state))
                       (swap! app-state assoc :new-style-value ""))
                   (swap! app-state assoc :status "Style property is not a valid keyword")))
    nil))

(defn- data-url [html]
  (str "data:text/html;base64,"
       (.encodeToString (Base64/getEncoder) (.getBytes html "UTF-8"))))

(defn- install-bridge! [^WebView web-view]
  (let [engine (.getEngine web-view)
        bridge (reify EditorBridge
                 (select [_ id]
                   (Platform/runLater #(dispatch! {:event :select :id id})))
                 (insert [_ tag target-id position]
                   (Platform/runLater
                    #(let [attrs (:pending-palette-attrs @app-state {})]
                       (swap! app-state dissoc :pending-palette-attrs)
                       (dispatch! {:event :insert
                                   :tag (keyword tag)
                                   :attrs attrs
                                   :target-id target-id
                                   :position (keyword position)}))))
                 (move [_ source-id target-id position]
                   (Platform/runLater
                    #(dispatch! {:event :move
                                 :source-id source-id
                                 :target-id target-id
                                 :position (keyword position)}))))]
    (.addListener (.stateProperty (.getLoadWorker engine))
                  (reify ChangeListener
                    (changed [_ _ _ new-state]
                      (when (= Worker$State/SUCCEEDED new-state)
                        (let [window ^JSObject (.executeScript engine "window")]
                          (.setMember window "editorBridge" bridge))))))))

(defn- palette-drag [tag attrs]
  (fn [event]
    (let [source-node ^Node (.getSource event)
          dragboard (.startDragAndDrop source-node (into-array TransferMode [TransferMode/COPY]))
          content (ClipboardContent.)]
      (.putString content (str "palette:" (name tag) ":" (pr-str attrs)))
      (.setContent dragboard content)
      (.consume event))))

(defn- web-drag-over [event]
  (let [dragboard (.getDragboard event)]
    (when (and (.hasString dragboard) (str/starts-with? (.getString dragboard) "palette:"))
      (.acceptTransferModes event (into-array TransferMode [TransferMode/COPY]))
      (when-let [web-view @web-view-instance]
        (try
          (.executeScript (.getEngine ^WebView web-view)
                          (format "window.editor&&window.editor.markAt(%f,%f)" (.getX event) (.getY event)))
          (catch Throwable _ nil)))
      (.consume event))))

(defn- web-drop [event]
  (let [dragboard (.getDragboard event)
        payload (when (.hasString dragboard) (.getString dragboard))]
    (if (and payload (str/starts-with? payload "palette:"))
      (let [[_ tag attrs-text] (str/split payload #":" 3)
            attrs (try (edn/read-string attrs-text) (catch Throwable _ {}))]
        (swap! app-state assoc :pending-palette-attrs attrs)
        (when-let [web-view @web-view-instance]
          (.executeScript (.getEngine ^WebView web-view)
                          (format "window.editor&&window.editor.paletteDrop(%f,%f,'%s')"
                                  (.getX event) (.getY event) tag)))
        (.setDropCompleted event true))
      (.setDropCompleted event false))
    (.consume event)))

(defn- toolbar-button [text event & [disabled?]]
  {:fx/type :button
   :text text
   :disable (boolean disabled?)
   :on-action (fn [_] (dispatch! {:event event}))})

(defn- menu-bar [app-state]
  (let [root (:root app-state)
        selected-id (:selected-id app-state)]
  {:fx/type :menu-bar
   :menus [{:fx/type :menu
            :text "File"
            :items [{:fx/type :menu-item :text "New" :accelerator [:shortcut :n]
                     :on-action (fn [_] (dispatch! {:event :new}))}
                    {:fx/type :menu-item :text "Open…" :accelerator [:shortcut :o]
                     :on-action (fn [_] (dispatch! {:event :open}))}
                    {:fx/type :separator-menu-item}
                    {:fx/type :menu-item :text "Save" :accelerator [:shortcut :s]
                     :on-action (fn [_] (dispatch! {:event :save}))}
                    {:fx/type :menu-item :text "Save As…" :accelerator [:shortcut :shift :s]
                     :on-action (fn [_] (dispatch! {:event :save-as}))}
                    {:fx/type :menu-item :text "Export Component…"
                     :on-action (fn [_] (dispatch! {:event :export}))}
                    {:fx/type :separator-menu-item}
                    {:fx/type :menu-item :text "Exit"
                     :on-action (fn [_] (dispatch! {:event :exit}))}]}
           {:fx/type :menu
            :text "Edit"
            :items [{:fx/type :menu-item :text "Undo" :accelerator [:shortcut :z]
                     :on-action (fn [_] (dispatch! {:event :undo}))}
                    {:fx/type :menu-item :text "Redo" :accelerator [:shortcut :shift :z]
                     :on-action (fn [_] (dispatch! {:event :redo}))}
                    {:fx/type :separator-menu-item}
                    {:fx/type :menu-item :text "Duplicate"
                     :on-action (fn [_] (dispatch! {:event :duplicate}))}
                    {:fx/type :menu-item :text "Move Up" :accelerator [:alt :up]
                     :disable (not (model/can-move-sibling? root selected-id -1))
                     :on-action (fn [_] (dispatch! {:event :move-up}))}
                    {:fx/type :menu-item :text "Move Down" :accelerator [:alt :down]
                     :disable (not (model/can-move-sibling? root selected-id 1))
                     :on-action (fn [_] (dispatch! {:event :move-down}))}
                    {:fx/type :menu-item :text "Delete Selected" :accelerator [:delete]
                     :disable (not (model/deletable? root selected-id))
                     :on-action (fn [_] (dispatch! {:event :delete}))}
                    {:fx/type :menu-item :text "Copy Hiccup" :accelerator [:shortcut :shift :c]
                     :on-action (fn [_] (dispatch! {:event :copy}))}]}]}))

(defn- palette-view []
  {:fx/type :scroll-pane
   :fit-to-width true
   :content {:fx/type :v-box
             :spacing 10
             :padding 12
             :children
             (mapv (fn [{:keys [name items]}]
                     {:fx/type :v-box
                      :spacing 5
                      :children
                      (into [{:fx/type :label
                              :text name
                              :style {:-fx-font-weight :bold
                                      :-fx-text-fill "#465166"}}]
                            (map (fn [{:keys [label tag attrs]}]
                                   {:fx/type :button
                                    :text label
                                    :max-width ##Inf
                                    :alignment :center-left
                                    :on-action (fn [_]
                                                 (dispatch! {:event :insert
                                                             :tag tag
                                                             :attrs (or attrs {})
                                                             :target-id (:selected-id @app-state)
                                                             :position :inside}))
                                    :on-drag-detected (palette-drag tag (or attrs {}))})
                                 items))})
                   model/palette-groups)}})

(defn- outline-view [root selected-id]
  {:fx/type :scroll-pane
   :fit-to-width true
   :content {:fx/type :v-box
             :padding 8
             :spacing 2
             :children
             (mapv (fn [{:keys [id depth label]}]
                     {:fx/type :button
                      :text label
                      :max-width ##Inf
                      :alignment :center-left
                      :style (cond-> {:-fx-padding (str "5 6 5 " (+ 8 (* depth 14)))
                                      :-fx-background-color :transparent}
                               (= id selected-id) (assoc :-fx-background-color "#dfe7ff"))
                      :on-action (fn [_] (dispatch! {:event :select :id id}))})
                   (model/outline root))}})

(defn- field-row [label value on-change]
  {:fx/type :v-box
   :spacing 3
   :children [{:fx/type :label :text label
               :style {:-fx-font-size 11 :-fx-text-fill "#687386"}}
              {:fx/type :text-field
               :text (str (or value ""))
               :on-text-changed on-change}]})

(defn- removable-row [key value change-event remove-event]
  {:fx/type :h-box
   :spacing 5
   :alignment :center-left
   :children [{:fx/type :label :text (str key) :min-width 80}
              {:fx/type :text-field
               :h-box/hgrow :always
               :text (str (or value ""))
               :on-text-changed #(dispatch! {:event change-event :key key :value %})}
              {:fx/type :button :text "×"
               :on-action (fn [_] (dispatch! {:event remove-event :key key}))}]})

(defn- inspector-view [app-state]
  (let [selected (model/find-node (:root app-state) (:selected-id app-state))]
    {:fx/type :scroll-pane
     :fit-to-width true
     :content
     {:fx/type :v-box
      :padding 14
      :spacing 10
      :children
      (if (model/text? selected)
        [{:fx/type :label :text "Text node"
          :style {:-fx-font-size 17 :-fx-font-weight :bold}}
         {:fx/type :text-area
          :text (str (:value selected))
          :wrap-text true
          :pref-row-count 5
          :on-text-changed #(dispatch! {:event :text-change :value %})}]
        (let [attrs (:attrs selected)
              generic (dissoc attrs :style)
              styles (:style attrs {})
              root (:root app-state)
              selected-id (:selected-id app-state)]
          (vec
           (concat
            [{:fx/type :label :text (str "<" (name (:tag selected)) ">")
              :style {:-fx-font-size 17 :-fx-font-weight :bold}}
             {:fx/type :h-box :spacing 5
              :children [(toolbar-button "↑" :move-up
                                         (not (model/can-move-sibling? root selected-id -1)))
                         (toolbar-button "↓" :move-down
                                         (not (model/can-move-sibling? root selected-id 1)))
                         (toolbar-button "Duplicate" :duplicate)
                         (toolbar-button "Delete" :delete
                                         (not (model/deletable? root selected-id)))]}
             {:fx/type :separator}
             {:fx/type :label :text "Attributes"
              :style {:-fx-font-weight :bold}}]
            (map (fn [[key value]] (removable-row key value :attr-change :attr-remove))
                 (sort-by (comp name key) generic))
            [{:fx/type :grid-pane
              :hgap 5 :vgap 5
              :children [{:fx/type :text-field
                          :prompt-text "attribute"
                          :text (:new-attr-key app-state)
                          :on-text-changed #(dispatch! {:event :ui-field :key :new-attr-key :value %})
                          :grid-pane/column 0}
                         {:fx/type :text-field
                          :prompt-text "value"
                          :text (:new-attr-value app-state)
                          :on-text-changed #(dispatch! {:event :ui-field :key :new-attr-value :value %})
                          :grid-pane/column 1}
                         {:fx/type :button :text "+"
                          :on-action (fn [_] (dispatch! {:event :add-attr}))
                          :grid-pane/column 2}]}
             {:fx/type :separator}
             {:fx/type :label :text "Styles"
              :style {:-fx-font-weight :bold}}]
            (map (fn [[key value]] (removable-row key value :style-change :style-remove))
                 (sort-by (comp name key) styles))
            [{:fx/type :grid-pane
              :hgap 5 :vgap 5
              :children [{:fx/type :text-field
                          :prompt-text "property"
                          :text (:new-style-key app-state)
                          :on-text-changed #(dispatch! {:event :ui-field :key :new-style-key :value %})
                          :grid-pane/column 0}
                         {:fx/type :text-field
                          :prompt-text "value"
                          :text (:new-style-value app-state)
                          :on-text-changed #(dispatch! {:event :ui-field :key :new-style-value :value %})
                          :grid-pane/column 1}
                         {:fx/type :button :text "+"
                          :on-action (fn [_] (dispatch! {:event :add-style}))
                          :grid-pane/column 2}]}]))))}}))

(defn- web-view [root selected-id]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [instance]
                 (reset! web-view-instance instance)
                 (install-bridge! instance))
   :on-deleted (fn [_] (reset! web-view-instance nil))
   :desc {:fx/type :web-view
          :url (data-url (preview/document-html root selected-id))
          :on-drag-over web-drag-over
          :on-drag-dropped web-drop}})

(defn- source-pane [app-state]
  {:fx/type :v-box
   :spacing 6
   :padding 10
   :style {:-fx-background-color "#f3f5f8"
           :-fx-border-color "#d8dde7"
           :-fx-border-width "1 0 0 0"}
   :children
   [{:fx/type :h-box
     :alignment :center-left
     :spacing 8
     :children [{:fx/type :label :text "Hiccup source"
                 :style {:-fx-font-weight :bold}}
                {:fx/type :region :h-box/hgrow :always}
                {:fx/type :button :text "Apply"
                 :disable (not (:source-dirty? app-state))
                 :on-action (fn [_] (dispatch! {:event :source-apply}))}
                {:fx/type :button :text "Copy"
                 :on-action (fn [_] (dispatch! {:event :copy}))}]}
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label :text "Export namespace"}
                {:fx/type :text-field
                 :text (:export-namespace app-state)
                 :pref-column-count 16
                 :on-text-changed #(dispatch! {:event :ui-field :key :export-namespace :value %})}
                {:fx/type :label :text "Component"}
                {:fx/type :text-field
                 :text (:export-name app-state)
                 :pref-column-count 16
                 :on-text-changed #(dispatch! {:event :ui-field :key :export-name :value %})}]}
    {:fx/type :text-area
     :text (:source-draft app-state)
     :style "-fx-font-family: 'Monospace'; -fx-font-size: 13px;"
     :pref-row-count 9
     :on-text-changed #(dispatch! {:event :source-edit :value %})}
    (if-let [error (:source-error app-state)]
      {:fx/type :label :text error
       :wrap-text true
       :style {:-fx-text-fill "#b42318"}}
      {:fx/type :label
       :text (if (:source-dirty? app-state) "Source draft has not been applied" "Source and canvas are synchronized")
       :style {:-fx-text-fill "#687386"}})]})

(defn app-view [app-state]
  (let [root (:root app-state)
        selected-id (:selected-id app-state)]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created #(reset! stage-instance %)
   :on-deleted (fn [_] (reset! stage-instance nil))
   :desc
   {:fx/type :stage
    :showing true
    :title (str (when (:dirty? app-state) "• ")
                "Reagent WYSIWYG — "
                (or (some-> (:file app-state) .getName) (str (get-in app-state [:component :name]))))
    :width 1440
    :height 920
    :min-width 1050
    :min-height 680
    :on-close-request #(exit! %)
    :scene
    {:fx/type :scene
     :root
     {:fx/type :v-box
      :children
      [(menu-bar app-state)
       {:fx/type :tool-bar
        :items [(toolbar-button "New" :new)
                (toolbar-button "Open" :open)
                (toolbar-button "Save" :save)
                {:fx/type :separator}
                (toolbar-button "Undo" :undo (empty? (:history app-state)))
                (toolbar-button "Redo" :redo (empty? (:future app-state)))
                (toolbar-button "Duplicate" :duplicate)
                (toolbar-button "Move Up" :move-up
                                (not (model/can-move-sibling? root selected-id -1)))
                (toolbar-button "Move Down" :move-down
                                (not (model/can-move-sibling? root selected-id 1)))
                (toolbar-button "Delete Selected" :delete
                                (not (model/deletable? root selected-id)))
                {:fx/type :separator}
                {:fx/type :label
                 :text "Tip: drag palette items onto the canvas; click any element to inspect it."
                 :style {:-fx-text-fill "#687386"}}]}
       {:fx/type :split-pane
        :v-box/vgrow :always
        :divider-positions [0.18 0.77]
        :items
        [{:fx/type :tab-pane
          :min-width 210
          :tabs [{:fx/type :tab :text "Palette" :closable false :content (palette-view)}
                 {:fx/type :tab :text "Outline" :closable false
                  :content (outline-view (:root app-state) (:selected-id app-state))}]}
         {:fx/type :v-box
          :children [{:fx/type :stack-pane
                      :v-box/vgrow :always
                      :children [(web-view (:root app-state) (:selected-id app-state))]}
                     (source-pane app-state)]}
         {:fx/type :border-pane
          :min-width 280
          :center (inspector-view app-state)}]}
       {:fx/type :h-box
        :padding 7
        :spacing 12
        :style {:-fx-background-color "#263248"}
        :children [{:fx/type :label :text (:status app-state)
                    :style {:-fx-text-fill :white}}
                   {:fx/type :region :h-box/hgrow :always}
                   {:fx/type :label
                    :text (str (count (model/outline (:root app-state))) " nodes")
                   :style {:-fx-text-fill "#cbd3e1"}}]}]}}}}))

(defn -main [& _]
  (let [renderer (fx/create-renderer
                  :middleware (fx/wrap-map-desc app-view))]
    (reset! renderer-instance renderer)
    (fx/mount-renderer app-state renderer)))
