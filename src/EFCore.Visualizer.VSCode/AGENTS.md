# AGENTS.md

Guidance for AI coding agents working on the VS Code extension. The repository-wide `AGENTS.md` at the root covers
the shared code and the Visual Studio extension; read it too.

## What this is

A VS Code extension that shows the execution plan of an EF Core query while debugging a .NET app. `README.md` is
the user-facing page (`vsce` publishes it to the Marketplace), so keep build and design details out of it.

It reuses the Visual Studio visualizer's debuggee-side code (`../EFCore.Visualizer.Core`) and its page templates
(`../EFCore.Visualizer.Core/Resources`). It has no Visual Studio dependency.

## Layout

- `src/extension.ts`: the commands, the tab per variable, the plan → SQL fallback, error reporting.
- `src/debuggee.ts`: the DAP `evaluate` calls into the debugged app.
- `src/webview.ts`: turns a generated page into webview HTML (asset URIs, theme, CSP), and the plan tab.
- `src/progress.ts`: the progress notification while a plan is retrieved.
- `scripts/build-helper.js`: builds `EFCore.Visualizer.Core` and lays it out in `dist/` like the VSIX:
  `dist/netcoreapp/EFCore.Visualizer.Core.dll` plus `dist/Resources/...`.
- `src/test/`: tests that run in a real VS Code (see Testing).
- `test-fixtures/SampleApp`: the EF Core 8 + SQLite app the tests debug.

## Build and test

Needs Node 22 and the .NET 8 SDK on `PATH`.

```sh
npm ci
npm run pretest   # tsc + build the helper into dist/ + build the sample app
npm test          # Linux without a display: xvfb-run -a npm test --ignore-scripts
```

`npm test` downloads VS Code into `.vscode-test/`, installs the real C# extension there, debugs the sample app
with vsdbg and runs the Mocha suites in `src/test/suite`. There's no lint step.

**On Windows, keep the checkout path short** (like `S:\src\EFCore.Visualizer`). Deep paths go past the 260-character
limit inside `.vscode-test`, and the C# extension's signature check, ripgrep and vsdbg then fail with `ENOENT`.

CI: `.github/workflows/vscode.yml` runs the same steps on `ubuntu-latest` when this folder or
`src/EFCore.Visualizer.Core` changes. There's no `.vscode/launch.json` for F5 debugging yet, and no `vsce`
packaging yet.

## How it works

The extension talks to the paused app only through DAP `evaluate` requests (`session.customRequest`), which the
debugger runs as func-eval inside the app:

1. `System.Reflection.Assembly.LoadFrom(@"<extension>/dist/netcoreapp/EFCore.Visualizer.Core.dll")`, on every
   visualize. Loading the same path again is cheap.
2. `IQueryableObjectSource.QueryPlanHelper.GetQueryFingerprint(expr)`: SHA-256 of the SQL with parameter values,
   without running the query. It decides whether an open tab is still up to date.
3. `QueryPlanHelper.GetPlanFile(expr)`, or `GetQueryFile(expr)` if the plan fails: each returns the path of an HTML
   page the helper wrote to the debuggee's temp folder.
4. The extension reads that page, deletes the file, and shows it in a webview.

Details that matter when changing this:

- **Use `context: 'repl'`.** vsdbg gives `repl` evaluations no time limit (`watch` is about 5 s, and `hover` allows
  no side effects). Plans for SQL Server, PostgreSQL and Oracle run the query, so they can take long.
- **Errors:** vsdbg reports an exception thrown by func-eval as a successful response with `failedEvaluation` in
  `presentationHint.attributes`. `evaluate` then reads the exception's `Message` child and throws an
  `EvaluationError`: `message` is short and shown to the user, and `detail` goes to the log.
- **Strings:** arguments are passed as C# verbatim strings (`csharpString`). Results come back in the debugger's
  display form and are decoded with `parseCSharpStringResult`.
- **No Cancel button.** vsdbg accepts DAP `cancel`, but while the app is in database code, the abort only lands
  when that code returns, and afterwards vsdbg refuses method calls until the user steps or continues.
- **Menu item:** the `when` clause matches the variable's runtime type (`EntityQueryable`, `IncludableQueryable`,
  `InternalDbSet`). vsdbg reports types as `Declared {Runtime}`, and variable names with a type suffix, such as
  `query [IOrderedQueryable]`. VS Code sets `variableType` in the Variables and Watch context menus.
- **Fallback:** only the plan retrieval is caught. If the plan fails, the SQL page is shown with a short warning.
  That tab gets no fingerprint, so visualizing the variable again retries the plan. If the SQL also fails, one error
  reports both. Full errors go to the "EFCore Visualizer" log output channel.
- **Tabs:** one tab per `sessionId + expression`. A revisit with the same fingerprint only reveals the tab; a
  different fingerprint refills it. Refresh always refills it. When a session ends, its tabs stay open but can't
  be refreshed.

### The webview

- The helper writes the page with absolute `file:` URLs for its `<script src>` and `<link href>`. A webview can't
  load `file:` URLs, so `toWebviewHtml` replaces each one with `webview.asWebviewUri(...)`, and `localResourceRoots`
  is set to exactly the folders those assets are in.
- **CSP:** `contentSecurityPolicy` allows only the webview's own resources, inline scripts and styles, and
  `'unsafe-eval'` (pev2 compiles its Vue template in the page). There is no network access, so even injected markup
  couldn't send the query or plan anywhere. Don't loosen it. The query and plan contain the app's data.
- **Theme:** in a webview, `prefers-color-scheme` follows the OS, not the VS Code theme. So `toWebviewHtml`
  rewrites each `(prefers-color-scheme: dark|light)` in `media="..."`, `@media` and `matchMedia(...)` to `all` or
  `not all` for the current theme, sets `--bg-color`/`--text-color` from the VS Code theme variables, and the tab
  re-renders when the theme changes.

## Rules

- **Don't use proposed VS Code APIs** (for example `debugVisualization`, which would give a hover visualizer).
  Extensions that use them can't be published to the Marketplace.
- **Don't add `extensionDependencies`** on the C# extension. Forks without it couldn't install the extension at all.
- **Code in `../EFCore.Visualizer.Core` is shared with Visual Studio.** A change there changes the VS visualizer
  too: build the VSIX as well (see the root `AGENTS.md`). Keep that code free of Visual Studio types.
- Templates put `{plan}` and `{query}` only inside double-quoted JavaScript strings. A test checks this.
- The debuggee and VS Code must share a file system (the helper DLL and the generated page are passed by path).

## Testing

`src/test/suite/debuggee.test.ts` debugs the sample app to its `// BREAKPOINT` line and checks, among others:
the helper calls; the command and its webview HTML, including the CSP; the SQL fallback and plan retry; escaping
in `Encode`; that templates put placeholders only where they are escaped; asset loading from a path with
special characters; the theme; which variables get the menu item; and tab reuse and refresh.
`progress.test.ts` checks the progress notification's timing and text.

The SQL Server, PostgreSQL and Oracle pages aren't covered by the tests (the sample app uses SQLite only).

## Not supported yet: netcoredbg

VS Code forks use netcoredbg instead of vsdbg (vsdbg is licensed for Microsoft products only). Findings from
netcoredbg 3.2.0, for when this is added:

- It matches method arguments exactly: no implicit conversions, casts, arrays or `new` in expressions. A generic
  extension method `this IQueryable<T>` works. `Include()` queries need a separately named
  `<T, TProperty>(this IIncludableQueryable<T, TProperty>)` method; an overload with the same name doesn't work.
- It has a fixed 5 s evaluation limit, so slow plans fail.
- It reports runtime types only, so the menu's `when` clause still works.
- A thrown exception comes back as a success whose type is the exception type, without `failedEvaluation`.
