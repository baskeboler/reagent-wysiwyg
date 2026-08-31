# Reagent WYSIWYG

A desktop visual editor for a safe, literal subset of Reagent Hiccup. The application shell is written in Clojure with [cljfx](https://github.com/cljfx/cljfx); the central design surface uses JavaFX WebView so HTML-oriented components look and behave like browser UI.

The editor never evaluates imported ClojureScript. It parses literal data, converts it to an editor-owned tree, and renders escaped HTML for the preview.

## Requirements

- JDK 17 or newer (JDK 25 is used by the current development environment)
- Clojure CLI
- Linux desktop with a working graphical display

Dependencies are pinned in `deps.edn`:

- Clojure 1.12.5
- cljfx 1.10.10
- rewrite-clj 1.2.55

## Run

```bash
clojure -M:run
```

Run the tests without opening JavaFX:

```bash
clojure -M:test
```

For a repeatable JavaFX/WebView bridge smoke test on a headless Linux machine:

```bash
xvfb-run -a clojure -M:smoke
```

## Build an executable uberjar

Build the application and all runtime dependencies into one executable JAR:

```bash
clojure -T:build uber
```

The default artifact is `target/reagent-wysiwyg-0.1.0-standalone.jar`. Run it with:

```bash
java --enable-native-access=ALL-UNNAMED \
  -jar target/reagent-wysiwyg-0.1.0-standalone.jar
```

Supply another artifact version when needed:

```bash
clojure -T:build uber :version '"0.2.0"'
```

Remove generated build output with `clojure -T:build clean`.

An editable example is available at `examples/sample.cljs`.

## Workflow

1. Drag a component from the Palette tab onto the canvas. Dropping near an element's top or bottom edge inserts before or after it; dropping in the middle inserts inside containers.
2. Click a rendered element or text node to select it. The Outline tab and inspector follow the selection.
3. Use the inspector to edit attributes, inline styles, or text. Use the toolbar to reorder, duplicate, delete, undo, and redo.
4. Edit the Hiccup pane directly when useful, then choose **Apply**. Invalid drafts leave the last valid canvas untouched.
5. Open a `.cljs` or `.cljc` file to edit a supported zero-argument component. If several components qualify, the editor asks which one to use.
6. Save to replace only the selected function body, or export a standalone namespace/component file. **Copy Hiccup** copies only the generated vector.

Clicking a palette item also inserts it inside the selected container, which is useful when drag-and-drop is inconvenient.

## Supported Hiccup

The root and element children use the shape:

```clojure
[:tag {:literal "attributes"
       :class "class names"
       :style {:color "#3157d5"
               :padding "1rem"}}
 child ...]
```

Supported tags:

- Layout: `div`, `section`, `header`, `main`, `footer`, `nav`, `article`, `aside`
- Text: `h1`, `h2`, `h3`, `p`, `span`, `strong`, `em`
- Lists: `ul`, `ol`, `li`
- Media/actions: `a`, `img`, `button`
- Forms: `form`, `label`, `input`, `textarea`, `select`, `option`

Attribute keys and style keys must be keywords. Attribute values must be strings, numbers, booleans, keywords, or `nil`. Children must be supported vectors, strings, or numbers. `img` and `input` cannot have children.

The following are deliberately rejected:

- Symbol-based custom components
- Function calls, bindings, event handlers, reader evaluation, or other executable forms
- Reagent fragments
- `script`, `style`, and unsupported HTML tags
- Collections or expressions as child content

Errors include the path of the invalid value when available.

## Existing files and saving

An importable component is a zero-argument `defn` whose only body expression is supported literal Hiccup:

```clojure
(ns example.ui)

(defn welcome-panel []
  [:main {:class "welcome"}
   [:h1 "Welcome"]
   [:p "This component is visually editable."]])
```

rewrite-clj preserves the namespace, unrelated forms, whitespace, and comments outside the selected Hiccup body. Once visually edited, that body is emitted in canonical formatting; comments and custom formatting inside it are not retained.

Before overwriting an existing file, the editor compares its SHA-256 digest with the version that was opened. If another program changed it, the available actions are Reload, Save As, and Cancel.

## Preview limitations

- The preview uses a neutral built-in stylesheet. Unknown `:class` names are preserved but project-specific CSS and Tailwind are not loaded.
- Links do not navigate, and user-provided JavaScript is never executed.
- Images may display when their `:src` is reachable by JavaFX WebView.
- Runtime data, event behavior, custom Reagent components, and application state are outside this first version.

## Project layout

- `model.clj` — editor tree and structural operations
- `hiccup.clj` — validation, parsing, and canonical generation
- `preview.clj` — escaped HTML and the small canvas bridge contract
- `source.clj` — component discovery, preservation, atomic writes, and export
- `state.clj` — pure application transitions and bounded history
- `main.clj` — cljfx views, WebView lifecycle, dialogs, clipboard, and file actions
