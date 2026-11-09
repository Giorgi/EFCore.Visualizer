# SampleApp

A console app for trying the visualizer in Visual Studio and VS Code. It uses EF Core 8 with SQLite, SQL Server,
PostgreSQL or Oracle. The VS Code extension's integration tests debug it too, with SQLite.

On first run it creates the database and fills it with 200 blogs and about 1,500 posts. The SQLite file is in the
output folder and is created again on every run. Server databases are kept: drop the database to start over, for
example after changing the model.

Use a new or empty database. The app creates its tables with EF Core's `EnsureCreated`, which does nothing in a
database that already has tables. Then the first query fails because the sample's tables don't exist, for example
with `42P01: relation "Blogs" does not exist` on PostgreSQL. (Oracle has no separate databases: its tables go into
the schema of the connection string's user.)

## Choosing the database

Pass `sqlite`, `sqlserver`, `postgres` or `oracle` as the first argument. Visual Studio has a launch profile for
each, and VS Code a launch configuration for each. Without an argument, the app uses the `Database` setting,
which is `sqlite` in `appsettings.json`.

## Connection strings

`appsettings.json` has a connection string for each server database:

| Name | Default |
|---|---|
| `SqlServer` | LocalDB, database `EFCoreVisualizerSample` |
| `Postgres` | `localhost`, database `efcore_visualizer_sample`, user and password `postgres` |
| `Oracle` | `localhost:1521/FREEPDB1`, user and password `efv_sample` (create the user first) |

To use your own without changing the committed file, set it in user secrets, which are stored in your user
profile, not in the repository:

```sh
dotnet user-secrets set ConnectionStrings:Postgres "Host=myserver;Database=sample;Username=me;Password=..."
```

An environment variable such as `ConnectionStrings__Postgres` overrides both. The `Database` setting can be
set the same ways.

## Trying the visualizer

Set a breakpoint on the line marked `// BREAKPOINT` in `Program.cs` and start debugging.

* **Visual Studio:** in `src/EFCore.Visualizer.slnx`, pick the **Experimental Instance + SampleApp** launch
  profile of the `EFCore.Visualizer` project and press F5. The Experimental Instance opens with `SampleApp.slnx`.
  Pick a database launch profile there and debug. Hover over a query and choose **Query Plan Visualizer**.
* **VS Code:** open `src/EFCore.Visualizer.VSCode` and press F5. In the window that opens, pick a
  `SampleApp: ...` configuration and debug. In **Variables**, hover over a query and click the
  **Visualize Query Plan** button at the end of its row, or right-click the query and choose it from the menu.

`recentPosts` (a join), `postsPerTopic` (an aggregate) and `activeBlogs` (a subquery) show the plan pages on
real data. The other variables are the cases the integration tests check, such as `broken`, whose plan fails
so the SQL is shown instead.
