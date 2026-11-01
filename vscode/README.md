# EFCore Visualizer for VS Code (spike)

Port of the Visual Studio visualizer. Instead of the VS-only `VisualizerObjectSource`, it talks to the
debuggee through plain DAP `evaluate` requests against the C# extension's `coreclr` debugger (vsdbg):

1. `System.Reflection.Assembly.LoadFrom(@"<extension>/dist/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll")`
2. `IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)` → path of the generated HTML plan page

`EFCore.Visualizer.DebuggeeHelper` (in `../src`) has no `Microsoft.VisualStudio.DebuggerVisualizers`
dependency. It compiles the sources in `../src/IQueryableObjectSource`, except the VS-only object source,
which is a thin adapter over `QueryPlanHelper`.

## Integration test

`src/test/suite/debuggee.test.ts` runs in a real VS Code (`@vscode/test-electron`) with
`ms-dotnettools.csharp` installed, debugs `test-fixtures/SampleApp` (EF Core 8 + SQLite), stops at the
`// BREAKPOINT` line and performs the two evaluations above (plus `GetQueryFile` and an error case).

Requires the .NET 8 SDK on `PATH`.

```sh
npm ci
npm run pretest            # tsc + build helper into dist/ + build the sample app
npm test                   # Linux without a display: xvfb-run -a npm test --ignore-scripts
```
