(ns reagent-wysiwyg.ui-smoke
  (:require [cljfx.api :as fx]
            [reagent-wysiwyg.main :as main]
            [reagent-wysiwyg.model :as model])
  (:import [javafx.application Platform]
           [javafx.scene.web WebView]))

(defn- wait-for [description predicate]
  (loop [attempt 0]
    (cond
      (predicate) true
      (>= attempt 120) (throw (ex-info (str "Timed out waiting for " description) {}))
      :else (do (Thread/sleep 50) (recur (inc attempt))))))

(defn- on-fx-thread [f]
  (let [result (promise)]
    (fx/run-later
      (try
        (deliver result {:value (f)})
        (catch Throwable error
          (deliver result {:error error}))))
    (let [{:keys [value error]} (deref result 5000 {:error (ex-info "FX operation timed out" {})})]
      (when error (throw error))
      value)))

(defn -main [& _]
  (try
    (main/-main)
    (wait-for "the JavaFX stage" #(some? @main/stage-instance))
    (wait-for "the WebView" #(some? @main/web-view-instance))
    (Thread/sleep 800)
    (let [root (:root @main/app-state)
          text-id (get-in root [:children 0 :children 0 :id])
          root-id (:id root)
          before (count (model/outline root))]
      (on-fx-thread
       #(.executeScript (.getEngine ^WebView @main/web-view-instance)
                        (str "window.editorBridge.select('" text-id "')")))
      (wait-for "selection bridge callback" #(= text-id (:selected-id @main/app-state)))
      (on-fx-thread
       #(.executeScript (.getEngine ^WebView @main/web-view-instance)
                        (str "window.editorBridge.insert('button','" root-id "','inside')")))
      (wait-for "insertion bridge callback"
                #(> (count (model/outline (:root @main/app-state))) before))
      (println "UI smoke test passed: stage, WebView, selection bridge, and insertion bridge"))
    (Platform/exit)
    (System/exit 0)
    (catch Throwable error
      (.printStackTrace error)
      (Platform/exit)
      (System/exit 1))))
