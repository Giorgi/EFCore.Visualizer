# EFCore Visualizer for VS Code (spike)

Port of the Visual Studio visualizer. Instead of the VS-only `VisualizerObjectSource`, it talks to the
debuggee through plain DAP `evaluate` requests against the C# extension's `coreclr` debugger (vsdbg):

1. `System.Reflection.Assembly.LoadFrom(@"<extension>/dist/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll")`
2. `IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)` → path of the generated HTML plan page

`EFCore.Visualizer.DebuggeeHelper` (in `../src`) has no `Microsoft.VisualStudio.DebuggerVisualizers`
dependency. It compiles the sources in `../src/IQueryableObjectSource`, except the VS-only object source,
which is a thin adapter over `QueryPlanHelper`.

## Usage

It needs a debugger that provides the `coreclr` debug type, such as the C# extension
(`ms-dotnettools.csharp`). It doesn't declare a dependency on it, because editors other than VS Code may not
use vsdbg; whether netcoredbg can run these evaluations is not tested yet.

While a .NET (`coreclr`) session is paused, right-click an EF Core query or `DbSet` in **Variables**
or **Watch** and choose **Visualize Query Plan**, or run *EFCore Visualizer: Visualize Query Plan* from the
Command Palette and type an expression. The menu item appears only when the variable's runtime type is
`EntityQueryable`, `IncludableQueryable` or `InternalDbSet`, whatever its declared type (`IOrderedQueryable`,
`IEnumerable`, ...). The plan opens in a webview beside the editor. If the plan can't be produced,
the error is shown and the SQL is displayed instead, as in Visual Studio.

Each variable gets its own tab, so plans can be compared side by side. Visualizing the same variable again
brings its tab to the front. If the query changed in between (another `Where` or `OrderBy`, a different query
assigned, or a different parameter value), the tab gets a fresh plan; the extension compares the query's SQL,
parameter values included, which it can generate without running the query. Changes the SQL doesn't show,
such as new data, need the tab's **Refresh** button. When the debug session ends, its tabs stay open as they are.

## Integration test

`src/test/suite/debuggee.test.ts` runs in a real VS Code (`@vscode/test-electron`) with
`ms-dotnettools.csharp` installed, debugs `test-fixtures/SampleApp` (EF Core 8 + SQLite), stops at the
`// BREAKPOINT` line and:

* performs the two evaluations above (plus `GetQueryFile` and a func-eval exception case);
* invokes the command with the same argument the Variables context menu passes and checks the webview HTML.

Requires the .NET 8 SDK on `PATH`.

```sh
npm ci
npm run pretest            # tsc + build helper into dist/ + build the sample app
npm test                   # Linux without a display: xvfb-run -a npm test --ignore-scripts
```

## Known gaps (spike)

* The debuggee loads the helper from the extension's `dist/` folder and writes pages to its temp folder,
  which the extension then reads. This assumes the debuggee and the extension host share a file system
  (true locally and for Remote-SSH/containers where the C# extension runs remotely; not for e.g.
  `pipeTransport` remote debugging).
* The integration test covers SQLite only (on Linux and Windows). The SQL Server, Postgres and Oracle pages
  were checked in Edge with sample plans, under the same Content-Security-Policy, but not in a real webview.
* No packaging (`vsce`) or Marketplace metadata yet.
