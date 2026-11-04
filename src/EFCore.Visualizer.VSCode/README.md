# EFCore Visualizer for VS Code

View the execution plan of an Entity Framework Core query while you debug, without leaving VS Code.
Supports SQL Server, PostgreSQL, Oracle and SQLite.

This is the VS Code version of the [EFCore Visualizer](https://github.com/Giorgi/EFCore.Visualizer) for
Visual Studio. It shows the same plan pages.

<!-- TODO: screenshot of a plan tab in VS Code -->

## Requirements

* VS Code 1.90 or newer. In **Watch**, the button and the menu item appear from VS Code 1.103 on; **Variables**
  and the Command Palette work in all versions.
* Microsoft's [C# extension](https://marketplace.visualstudio.com/items?itemName=ms-dotnettools.csharp)
  (`ms-dotnettools.csharp`), which provides the .NET debugger.
* An app that uses EF Core 7 or newer, on .NET 6 or newer.

VS Code forks such as Cursor or VSCodium aren't supported yet. They use a different .NET debugger (netcoredbg),
which can't run the calls the extension needs.

## Usage

1. Debug your app and pause it, for example at a breakpoint, where a query is in scope.
2. In **Variables** or **Watch**, hover over the query (an `IQueryable` from EF Core, or a `DbSet`) and click the
   **Visualize Query Plan** button at the end of its row. Or right-click it and choose **Visualize Query Plan**.

The plan opens in a tab beside the editor. The menu item appears only on EF Core queries, whatever the
variable's declared type (`IQueryable`, `IOrderedQueryable`, `IEnumerable`, ...).

You can also run **EFCore Visualizer: Visualize Query Plan** from the Command Palette and type an expression
that returns a query, such as `query`, `context.Blogs` or `context.Posts.Take(5)`. Lambdas that read a property,
such as `Where(b => b.Rating > 3)` or `OrderBy(b => b.Url)`, don't work there: the debugger can't turn them into
the expression trees EF Core needs. Put such a query in a variable in your code instead.

### Tabs

* Each variable gets its own tab, so you can compare plans side by side.
* Visualizing the same variable again brings its tab to the front. If the query changed in the meantime (another
  `Where` or `OrderBy`, a new query assigned, a different parameter value), the tab gets the new plan.
* The **Refresh** button in the tab's title bar gets the plan again even if the query didn't change, for example
  after the data changed.
* The **Copy SQL** button next to it copies the query's SQL, with its parameter values declared, ready to run in
  your database tool. It works after the debug session has ended too.
* When the debug session ends, its tabs stay open.

The page follows the VS Code color theme.

## Important: the query runs

For SQL Server, PostgreSQL and Oracle, the extension shows the *actual* plan, so it **runs the query** in your
app, on your app's connection and transaction:

* Getting the plan takes as long as the query. A notification shows the elapsed time.
* It can't be cancelled. Your app stays paused until the query finishes.
* The debugger sets no time limit, unlike Visual Studio, where plans that take more than about 5 seconds fail.
  The query is still limited by your app's database command timeout: 30 seconds by default for SQL Server and
  PostgreSQL.

For SQLite, the extension only asks for the estimated plan (`EXPLAIN QUERY PLAN`), so the query doesn't run.

## When there's no plan

If the extension can't get the plan, a short warning says why, and the tab shows the query's SQL instead.
Visualizing the variable again tries the plan again. The full error is in the **EFCore Visualizer** output channel
(**View > Output**).

## Known issues

* There's no visualizer icon in the debug hover or next to the variable, as in Visual Studio. VS Code's API for
  that is still a proposal ([microsoft/vscode#197287](https://github.com/microsoft/vscode/issues/197287)), so
  the extension uses the context menu instead.
* Your app must see the same file system as VS Code. It loads a helper DLL from the extension's folder and
  writes the plan page to its temp folder, where the extension reads it. That works for local debugging;
  remote setups (Remote-SSH, containers, WSL) haven't been tested.
* If your app uses Application Insights, getting the plan can fail with "Cannot evaluate expression since the
  function evaluation requires all threads to run". The same happens in Visual Studio
  ([#28](https://github.com/Giorgi/EFCore.Visualizer/issues/28)). Disable Application Insights while debugging.

## Credits

The plan pages use [pev2](https://github.com/dalibo/pev2/),
[html-query-plan](https://github.com/JustinPealing/html-query-plan),
[Treeflex](https://dumptyd.github.io/treeflex/) and [highlight.js](https://highlightjs.org/).
