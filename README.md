![EFCore.Visualizer](doc/IconMedium.png "EFCore.Visualizer")

# Entity Framework Core Query Plan Visualizer

View Entity Framework Core query plan directly inside Visual Studio.

[![Visual Studio Marketplace Version](https://img.shields.io/visual-studio-marketplace/v/GiorgiDalakishvili.EFCoreVisualizer?style=for-the-badge&logo=visualstudio&label=Download%20Now&color=purple)](https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.EFCoreVisualizer)
[![Visual Studio Marketplace Downloads](https://img.shields.io/visual-studio-marketplace/d/GiorgiDalakishvili.EFCoreVisualizer?style=for-the-badge)](https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.EFCoreVisualizer)
[![Visual Studio Marketplace Rating](https://img.shields.io/visual-studio-marketplace/r/GiorgiDalakishvili.EFCoreVisualizer?style=for-the-badge)](https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.EFCoreVisualizer&ssr=false#review-details)


## Introduction

With Entity Framework Core query plan debugger visualizer, you can view the query plan of your queries directly inside Visual Studio. Currently, the visualizer supports SQL Server and PostgreSQL.

> [!IMPORTANT] 
> The visualizer requires **Visual Studio Version 17.9.0 ([Released on February 13th](https://devblogs.microsoft.com/visualstudio/visual-studio-2022-17-9-now-available/)) or newer** and supports **EF Core 7 or newer**.

## Usage

After installing the [extension from the marketplace](https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.EFCoreVisualizer), a new debugger visualizer will be added to Visual Studio. When debugging, hover over your queries and there will be an option to view the query plan:

![VariableVisualizer](doc/VariableVisualizer.png)

Click on 'Query Plan Visualizer' and the query plan will be displayed for your query.

### SQL Server:

![Sql Server Plan](doc/SqlPlan1.png)

![Sql Server Plan](doc/SqlPlan2.png)

### PostgreSQL:

![PostgreSQL Plan](doc/PostgreSQLPlan2.png)

![PostgreSQL Plan](doc/PostgreSQLPlan1.png)

## Known Issues:

 - If query plan extraction takes more than 5 seconds, you will get [Evaluation timed out error](https://github.com/Giorgi/EFCore.Visualizer/issues/25)
 - If your project uses Application Insights, you might get [Cannot evaluate expression since the function evaluation requires all threads to run.](https://github.com/Giorgi/EFCore.Visualizer/issues/28) when viewing query plan. **Workaround** - disable Application Insights when running your project with a debugger attached.

## Credits

This extension uses [pev2](https://github.com/dalibo/pev2/) and [html-query-plan](https://github.com/JustinPealing/html-query-plan) to display query plans.
