(ns reagent-wysiwyg.view-test
  (:require [clojure.test :refer [deftest is]]
            [reagent-wysiwyg.main :as main]
            [reagent-wysiwyg.state :as state]))

(defn- action-handlers [description]
  (->> (tree-seq coll? seq description)
       (filter map?)
       (keep :on-action)))

(deftest every-action-handler-accepts-the-javafx-event
  (let [events (atom [])
        description (main/app-view (state/initial-state))
        handlers (vec (action-handlers description))]
    (is (pos? (count handlers)))
    (with-redefs [main/dispatch! #(swap! events conj %)]
      (doseq [handler handlers]
        (handler ::javafx-event)))
    (is (= (count handlers) (count @events)))))
