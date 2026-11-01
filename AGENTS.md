# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

## What this is

A Visual Studio debugger visualizer (VSIX) that shows the execution plan of an EF Core `IQueryable` while debugging. It supports Visual Studio 17.9+ (manifest range `[17.9, 19.0)`) and EF Core 7+.

## Build

There are no tests and no lint step. Build the solution with MSBuild from a Visual Studio developer environment. `dotnet build` does not work, because the VSIX project needs the VS SDK build targets.

```
nuget restore src/EFCore.Visualizer.sln
msbuild src/EFCore.Visualizer.sln /t:Rebuild
```

CI runs the same build. `.github/workflows/build.yml` builds the VSIX in Release on every push and pull request to `main`, and uploads `EFCore.Visualizer.vsix` as an unzipped artifact. `.github/workflows/codeql-analysis.yml` builds the solution for CodeQL. To debug, run `EFCore.Visualizer` from Visual Studio (it starts the Experimental Instance), then debug any app that uses EF Core.

**Build order:** the solution has no project reference between the two projects. `EFCore.Visualizer.csproj` packs `..\IQueryableObjectSource\bin\$(Configuration)\net6\IQueryableObjectSource.dll` into the VSIX as a `Content` file. Build `IQueryableObjectSource` first, in the same configuration, or the VSIX will be missing that DLL or will contain an old copy.

## Architecture

The code is split across two processes:

- **`src/EFCore.Visualizer`** (net48, runs inside the VS process). It uses VisualStudio.Extensibility in-process (`RequiresInProcessHosting = true`) together with the Community.VisualStudio.Toolkit.
  - `EFCoreQueryableDebuggerVisualizerProvider` registers the visualizer for `EntityQueryable<T>` and `IncludableQueryable<T,P>`. It names the debuggee-side object source by its assembly-qualified string name. The menu label ("Query Plan Visualizer") comes from `.vsextension/string-resources.json`.
  - `QueryPlanUserControl` is a WPF control that hosts WebView2. It sends two requests to the object source, `GetQuery` and then `GetQueryPlan`. Each reply is a path to a generated HTML file, which the control opens in WebView2. If the plan request fails, the control shows the error and displays the query-only page instead. It also passes the VS theme to the page: it sets `PreferredColorScheme` and injects the `--bg-color` CSS variable after navigation.
- **`src/IQueryableObjectSource`** (net6, loaded into the *debuggee* process). It is installed into the VSIX under `netcoreapp\`.
  - `EFCoreQueryableObjectSource.TransferData` reads a 1-byte `OperationType` request and writes the reply with `StreamExtensions`: a `bool isError` followed by a `string`, written with `BinaryWriter` and `Encoding.Default`. `QueryPlanUserControl.ReadString` reads the reply in the same format. If you change one side, change the other side too.
  - `OperationType.cs` is linked into the VS project (`<Compile Include=... Link=...>`), so both sides use the same enum.
  - `GetDatabaseProvider` selects a `DatabaseProvider` subclass by the `DbCommand` type's full name, given as a **string**. The project references only `Microsoft.EntityFrameworkCore.Relational` 7.0.0 and no provider packages, so provider code may use only `System.Data.Common` APIs.
  - Each provider runs its own plan query against the app's own connection and transaction. SQL Server, Postgres and Oracle **execute the query** to get an actual plan; SQLite does not:
    - SQL Server: `SET STATISTICS XML ON`
    - Postgres: `EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS)`
    - Oracle: `statistics_level = ALL`, then `DBMS_XPLAN.DISPLAY_CURSOR`
    - SQLite: `EXPLAIN QUERY PLAN`

    Providers that change session settings put them back in a `finally` block. The VS debugger stops an evaluation after about 5 seconds, so a slow plan query fails with a timeout. The README lists this as a known issue.
  - The plan is placed into `Resources/<Provider>/template.html`, which replaces the `{plan}` and `{query}` placeholders. The result is written as a random `.html` file next to the template, and the VS side deletes that file when the control unloads. `GetPlanDirectory` returns the folder name, and `Encode` sets the escaping. The default escaping is JS string escaping. SQLite overrides it, because it creates the HTML tree itself.
  - The object source locates `Resources` relative to its own DLL: two directory levels up from the DLL, then `Resources`.

### Adding a database provider

1. Add a `DatabaseProvider` subclass in `IQueryableObjectSource`.
2. Add its command type name to the `GetDatabaseProvider` switch.
3. Add a `Resources/<Name>/template.html`, plus any JS/CSS it needs. Shared assets are in `Resources/Common`.
4. In `EFCore.Visualizer.csproj`, add a `<Content Include="Resources\<Name>\*.*"><IncludeInVSIX>true</IncludeInVSIX></Content>` entry, and a `<None Remove>` entry for the template. Without these, the files do not ship.

## Release bookkeeping

The extension version is in `src/EFCore.Visualizer/source.extension.vsixmanifest` (`Identity Version`). The marketplace ID is `GiorgiDalakishvili.EFCoreVisualizer`. Third-party visualization libraries (pev2, html-query-plan, Treeflex, highlight.js) are vendored under `Resources/`, and their licenses are in `ThirdPartyLicenses/`.
