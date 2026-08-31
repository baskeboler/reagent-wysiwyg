(ns example.sample)

;; Forms outside the selected component are preserved when saving.
(def app-name "Reagent WYSIWYG")

(defn welcome-panel []
  [:main {:class "welcome-panel"
          :style {:max-width "720px"
                  :margin "0 auto"
                  :padding "2rem"}}
   [:header
    [:h1 "Build Reagent interfaces visually"]
    [:p "Drag components into the canvas, then refine their attributes and styles."]]
   [:section
    [:h2 "Contact details"]
    [:form
     [:label {:for "email"} "Email"]
     [:input {:id "email" :type "text" :placeholder "you@example.com"}]
     [:button {:type "button"} "Continue"]]]])

(defn compact-note []
  [:aside [:strong "Tip: "] [:span "Choose the component to edit when opening this file."]])
