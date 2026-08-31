# Repository Guidelines

## Project Structure & Module Organization

Application code lives in `src/reagent_wysiwyg/`. Keep pure editor-tree operations in `model.clj`, Hiccup validation and serialization in `hiccup.clj`, HTML generation in `preview.clj`, file preservation/export in `source.clj`, state transitions in `state.clj`, and JavaFX/UI side effects in `main.clj`. Tests mirror those namespaces under `test/reagent_wysiwyg/`. `examples/sample.cljs` is an editable fixture, `build.clj` defines tools.build tasks, and generated output belongs in ignored `target/`.

## Build, Test, and Development Commands

- `clojure -M:run` — launch the desktop editor; requires a graphical display.
- `clojure -M:test` — run all headless `clojure.test` suites.
- `xvfb-run -a clojure -M:smoke` — exercise JavaFX startup and the WebView bridge on Linux without a display.
- `clojure -T:build uber` — create `target/reagent-wysiwyg-0.1.0-standalone.jar`.
- `clojure -T:build clean` — remove generated build output.

Run the uberjar with `java --enable-native-access=ALL-UNNAMED -jar target/reagent-wysiwyg-0.1.0-standalone.jar`.

## Coding Style & Naming Conventions

Use standard Clojure formatting with two-space indentation and aligned map values. Name functions and vars in `kebab-case`; namespace paths use underscores (`reagent_wysiwyg`) while namespace declarations use hyphens (`reagent-wysiwyg.model`). Prefer small pure transformations in model/state namespaces and isolate dialogs, clipboard, filesystem writes, and JavaFX thread work. No formatter or linter is currently configured, so keep diffs consistent with surrounding code and run `git diff --check`.

## Testing Guidelines

Use `clojure.test`; name files `*_test.clj` and tests with behavior-oriented `deftest` names. Add focused coverage for parser rejection, structural invariants, selection/history behavior, and source preservation. Register new test namespaces in `test_runner.clj`. UI callback or WebView changes should also pass the Xvfb smoke test.

## Security & Data Safety

Never evaluate imported ClojureScript. Preserve the literal-Hiccup whitelist, escape preview HTML, reserve editor bridge attributes, and retain external-change detection plus atomic writes.

## Commit & Pull Request Guidelines

Recent commits use concise, imperative subjects such as `Add executable uberjar build configuration`. Keep commits scoped. Pull requests should describe behavior changes, list validation commands, link relevant issues, and include screenshots for visible UI changes. Call out file-format or supported-Hiccup compatibility changes explicitly.
