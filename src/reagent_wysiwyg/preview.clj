(ns reagent-wysiwyg.preview
  (:require [clojure.string :as str]
            [reagent-wysiwyg.model :as model]))

(defn escape-html [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- css-style [styles]
  (->> styles
       (sort-by (comp name key))
       (map (fn [[key value]] (str (name key) ":" value)))
       (str/join ";")))

(defn- attr-html [[key value]]
  (cond
    (= key :style) (str " style=\"" (escape-html (css-style value)) "\"")
    (= key :class) (str " class=\"" (escape-html value) "\"")
    (true? value) (str " " (name key))
    (or (false? value) (nil? value)) ""
    :else (str " " (name key) "=\"" (escape-html value) "\"")))

(declare node-html)

(defn node-html [node selected-id]
  (if (model/text? node)
    (str "<span class=\"editor-text"
         (when (= selected-id (:id node)) " editor-selected")
         "\" data-editor-id=\"" (:id node) "\" draggable=\"true\">"
         (escape-html (:value node)) "</span>")
    (let [tag (name (:tag node))
          attrs (->> (:attrs node)
                     (remove (comp model/reserved-attrs key))
                     (sort-by (comp name key))
                     (map attr-html)
                     (apply str))
          editor-attrs (str " data-editor-id=\"" (:id node) "\" draggable=\"true\""
                            (when (= selected-id (:id node)) " data-editor-selected=\"true\""))]
      (if (contains? model/void-tags (:tag node))
        (str "<" tag editor-attrs attrs ">")
        (str "<" tag editor-attrs attrs ">"
             (apply str (map #(node-html % selected-id) (:children node)))
             "</" tag ">")))))

(def editor-css
  "html,body{margin:0;min-height:100%;font-family:Inter,system-ui,sans-serif;background:#f7f8fa;color:#172033}body{padding:28px;box-sizing:border-box}*{box-sizing:border-box}[data-editor-id]{position:relative;min-height:18px}div[data-editor-id],section[data-editor-id],header[data-editor-id],main[data-editor-id],footer[data-editor-id],nav[data-editor-id],article[data-editor-id],aside[data-editor-id],form[data-editor-id],ul[data-editor-id],ol[data-editor-id]{min-height:42px;padding:10px;margin:5px 0;border:1px dashed #ccd2dc;border-radius:6px}button,input,textarea,select{font:inherit;padding:7px 10px;margin:4px}button{background:#3157d5;color:white;border:0;border-radius:5px}a{color:#3157d5}img{min-width:80px;min-height:40px;background:#e8ebf1;border:1px solid #ccd2dc}[data-editor-selected=true],.editor-selected{outline:3px solid #5b7cfa!important;outline-offset:2px}.editor-drop-inside{box-shadow:inset 0 0 0 3px #27a96b}.editor-drop-before:before,.editor-drop-after:after{content:'';display:block;position:absolute;left:0;right:0;height:3px;background:#27a96b;z-index:999}.editor-drop-before:before{top:-3px}.editor-drop-after:after{bottom:-3px}.editor-text{white-space:pre-wrap;min-width:2px;display:inline-block}h1,h2,h3,p{margin-top:.4em;margin-bottom:.4em}")

(def bridge-script
  "(function(){
    let dragId=null, marker=null;
    function closest(x,y){const e=document.elementFromPoint(x,y);return e&&e.closest('[data-editor-id]');}
    function position(el,y){const r=el.getBoundingClientRect(),p=(y-r.top)/Math.max(r.height,1);return p<.25?'before':p>.75?'after':'inside';}
    function clear(){if(marker){marker.classList.remove('editor-drop-before','editor-drop-after','editor-drop-inside');marker=null;}}
    function mark(el,pos){clear();marker=el;el.classList.add('editor-drop-'+pos);}
    document.addEventListener('click',e=>{const el=e.target.closest('[data-editor-id]');if(el&&window.editorBridge){e.preventDefault();e.stopPropagation();window.editorBridge.select(el.dataset.editorId);}});
    document.addEventListener('dragstart',e=>{const el=e.target.closest('[data-editor-id]');if(el){dragId=el.dataset.editorId;e.dataTransfer.setData('text/editor-node',dragId);e.dataTransfer.effectAllowed='move';}});
    document.addEventListener('dragover',e=>{const el=closest(e.clientX,e.clientY);if(el){e.preventDefault();mark(el,position(el,e.clientY));}});
    document.addEventListener('dragleave',e=>{if(!e.relatedTarget)clear();});
    document.addEventListener('drop',e=>{const el=closest(e.clientX,e.clientY);if(el&&dragId&&window.editorBridge){e.preventDefault();const pos=position(el,e.clientY);window.editorBridge.move(dragId,el.dataset.editorId,pos);}dragId=null;clear();});
    window.editor={paletteDrop:function(x,y,tag){const el=closest(x,y);if(el&&window.editorBridge){const pos=position(el,y);window.editorBridge.insert(tag,el.dataset.editorId,pos);clear();return true;}return false;},markAt:function(x,y){const el=closest(x,y);if(el){mark(el,position(el,y));return true;}clear();return false;},clear:clear};
    document.addEventListener('click',e=>{if(e.target.closest('a'))e.preventDefault();},true);
  })();")

(defn document-html [root selected-id]
  (str "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
       editor-css
       "</style></head><body>"
       (node-html root selected-id)
       "<script>" bridge-script "</script></body></html>"))
