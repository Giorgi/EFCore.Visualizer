![EFCore.Visualizer](doc/IconMedium.png "EFCore.Visualizer")

# Entity Framework Core Query Plan Visualizer

View Entity Framework Core query plan directly inside Visual Studio

## Introduction

With Entity Framework Core query plan debugger visualizer you can view query plan of your queries directly inside Visual Studio. Currently, the visualizer supports SQL Server and PostgreSQL.

The visualizer requires **Visual Studio Version 17.9.0 or newer**.

## Usage

After installing the extension from marketplace, a new debugger visualizer will be added to Visual Studio. When debugging, hover over your queries and there will be an option to view query plan:
![VariableVisualizer](doc/VariableVisualizer.png)

Click on 'Query Plan Visualizer' and query plan will be displayed for your query:

![Sql Server Plan](doc/SqlPlan1.png)


![Sql Server Plan](doc/SqlPlan2.png)

![PostgreSQL Plan](doc/PostgreSQLPlan2.png)

![PostgreSQL Plan](doc/PostgreSQLPlan1.png)
