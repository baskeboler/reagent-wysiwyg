(ns reagent-wysiwyg.hiccup
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [reagent-wysiwyg.model :as model]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]))

(defn validation-error [message path]
  (ex-info message {:type ::invalid-hiccup :path path}))

(defn- scalar? [value]
  (or (string? value) (number? value) (boolean? value)
      (keyword? value) (nil? value)))

(defn- validate-style [style path]
  (when-not (map? style)
    (throw (validation-error ":style must be a literal map" path)))
  (doseq [[key value] style]
    (when-not (keyword? key)
      (throw (validation-error "Style property names must be keywords" (conj path key))))
    (when-not (scalar? value)
      (throw (validation-error "Style values must be literal scalars" (conj path key)))))
  style)

(defn- validate-attrs [attrs path]
  (when-not (map? attrs)
    (throw (validation-error "Element attributes must be a literal map" path)))
  (doseq [[key value] attrs]
    (when-not (keyword? key)
      (throw (validation-error "Attribute names must be keywords" (conj path key))))
    (when (contains? model/reserved-attrs key)
      (throw (validation-error (str key " is reserved by the editor") (conj path key))))
    (if (= key :style)
      (validate-style value (conj path key))
      (when-not (scalar? value)
        (throw (validation-error "Attribute values must be literal scalars" (conj path key))))))
  attrs)

(declare form->node)

(defn- element-form->node [form path]
  (when (empty? form)
    (throw (validation-error "Hiccup vectors cannot be empty" path)))
  (let [tag (first form)]
    (when-not (keyword? tag)
      (throw (validation-error "Element tags must be keywords, not symbols or expressions" (conj path 0))))
    (when-not (contains? model/supported-tags tag)
      (throw (validation-error (str "Unsupported element tag " tag) (conj path 0))))
    (let [has-attrs? (map? (second form))
          attrs (if has-attrs? (second form) {})
          child-forms (subvec form (if has-attrs? 2 1))]
      (validate-attrs attrs (conj path 1))
      (when (and (contains? model/void-tags tag) (seq child-forms))
        (throw (validation-error (str tag " cannot have children") path)))
      (model/element-node tag attrs
                          (mapv #(form->node %1 (conj path %2))
                                child-forms
                                (range (if has-attrs? 2 1) (count form)))))))

(defn form->node
  ([form] (form->node form []))
  ([form path]
   (cond
     (vector? form) (element-form->node form path)
     (or (string? form) (number? form)) (model/text-node form)
     :else (throw (validation-error
                   "Children must be literal strings, numbers, or supported Hiccup vectors"
                   path)))))

(defn node->form [editor-node]
  (if (model/text? editor-node)
    (:value editor-node)
    (let [{:keys [tag attrs children]} editor-node]
      (into (cond-> [tag] (seq attrs) (conj attrs))
            (map node->form children)))))

(defn canonical-string [editor-node]
  (-> (with-out-str
        (binding [pprint/*print-right-margin* 88]
          (pprint/write (node->form editor-node)
                        :dispatch pprint/code-dispatch)))
      str/trim))

(defn parse-string [source]
  (try
    (let [root (parser/parse-string-all source)
          forms (vec (node/child-sexprs root))]
      (when-not (= 1 (count forms))
        (throw (validation-error "Enter exactly one Hiccup vector" [])))
      (form->node (first forms)))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable error
      (throw (ex-info (str "Unable to parse Hiccup: " (.getMessage error))
                      {:type ::parse-error}
                      error)))))

(defn error-message [error]
  (let [{:keys [path]} (ex-data error)]
    (str (.getMessage error)
         (when (seq path) (str " at " (pr-str path))))))
