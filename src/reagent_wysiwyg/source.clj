(ns reagent-wysiwyg.source
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [reagent-wysiwyg.hiccup :as hiccup]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files Path StandardCopyOption]
           [java.security MessageDigest]))

(defn sha256 [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str text) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- defn-shape [form]
  (when (and (seq? form) (= 'defn (first form)) (symbol? (second form)))
    (let [items (vec form)
          after-name 2
          after-doc (if (string? (get items after-name)) (inc after-name) after-name)
          params-index (if (map? (get items after-doc)) (inc after-doc) after-doc)
          params (get items params-index)
          body-index (inc params-index)]
      (when (and (vector? params)
                 (empty? params)
                 (= (inc body-index) (count items)))
        {:name (second items)
         :body (get items body-index)
         :body-sexpr-index body-index}))))

(defn- namespace-name [root]
  (some (fn [child]
          (when (node/sexpr-able? child)
            (let [form (node/sexpr child)]
              (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                (second form)))))
        (node/children root)))

(defn discover-components [source]
  (let [root (parser/parse-string-all source)
        ns-name (namespace-name root)]
    (->> (node/children root)
         (keep-indexed
          (fn [top-index child]
            (when (and (= :list (node/tag child)) (node/sexpr-able? child))
              (when-let [{:keys [name body body-sexpr-index]} (defn-shape (node/sexpr child))]
                (try
                  (let [editor-root (hiccup/form->node body)]
                    {:name name
                     :namespace ns-name
                     :top-index top-index
                     :body-sexpr-index body-sexpr-index
                     :root editor-root})
                  (catch Throwable _ nil))))))
         vec)))

(defn read-document [file]
  (let [file (io/file file)
        source (slurp file)
        extension (some-> (.getName file) (str/split #"\.") last str/lower-case)]
    (when-not (#{"cljs" "cljc"} extension)
      (throw (ex-info "Only .cljs and .cljc files can be opened" {:file file})))
    {:file file
     :source source
     :digest (sha256 source)
     :components (discover-components source)}))

(defn replace-component [source component editor-root]
  (let [root (parser/parse-string-all source)
        top-children (vec (node/children root))
        list-node (nth top-children (:top-index component))
        list-children (vec (node/children list-node))
        sexpr-indices (vec (keep-indexed #(when (node/sexpr-able? %2) %1)
                                         list-children))
        body-child-index (nth sexpr-indices (:body-sexpr-index component))
        replacement (node/coerce (hiccup/node->form editor-root))
        updated-list (node/list-node (assoc list-children body-child-index replacement))
        updated-root (node/forms-node (assoc top-children (:top-index component) updated-list))]
    (node/string updated-root)))

(defn current-file-digest [file]
  (when (and file (.exists (io/file file)))
    (sha256 (slurp file))))

(defn externally-modified? [file expected-digest]
  (and file expected-digest (not= expected-digest (current-file-digest file))))

(defn atomic-write! [file content]
  (let [target (.toPath (io/file file))
        parent (.getParent target)
        prefix (str "." (.getFileName target) ".")
        temp (Files/createTempFile parent prefix ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString temp content StandardCharsets/UTF_8
                         (make-array java.nio.file.OpenOption 0))
      (try
        (Files/move temp target
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move temp target
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists temp)))
    file))

(defn exported-source [namespace-name component-name editor-root]
  (str "(ns " namespace-name ")\n\n"
       "(defn " component-name " []\n"
       (->> (str/split-lines (hiccup/canonical-string editor-root))
            (map #(str "  " %))
            (str/join "\n"))
       ")\n"))
