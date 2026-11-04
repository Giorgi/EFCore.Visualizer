# Changelog

## 1.0.0

The first release. It brings the EFCore Visualizer for Visual Studio to VS Code.

* **Query plans while debugging:** see the execution plan of an EF Core query for SQL Server, PostgreSQL, Oracle
  and SQLite.
* **Three ways to open a plan:**
  * click the button at the end of the query's row in **Variables** or **Watch**;
  * right-click the query in **Variables** or **Watch**;
  * run the command from the Command Palette and type an expression.
* **One tab per variable:** new plans open side by side. If you visualize a variable again, its tab comes to the
  front. The tab gets a new plan if the query changed.
* **Refresh and Copy SQL:** **Refresh** gets the plan again. **Copy SQL** copies the query's SQL with its parameters.
* **SQL when there's no plan:** the tab shows the query's SQL, and a warning says why there's no plan.
* **Your color theme:** the plan follows the VS Code color theme.
