using System.Data;
using System;
using System.Data.Common;
using System.IO;
using System.Linq;

namespace IQueryableObjectSource;

abstract class DatabaseProvider(DbCommand command)
{
    protected DbCommand Command { get; } = command;

    public string ExtractPlan()
    {
        var needToClose = false;

        try
        {
            if (Command.Connection.State != ConnectionState.Open)
            {
                needToClose = true;
                Command.Connection.Open();
            }

            return ExtractPlanInternal(Command);
        }
        finally
        {
            if (needToClose)
            {
                Command.Connection.Close();
            }
        }
    }

    protected abstract string ExtractPlanInternal(DbCommand command);
    internal abstract string GetPlanDirectory(string baseDirectory);
}

class SqlServerDatabaseProvider(DbCommand command) : DatabaseProvider(command)
{
    protected override string ExtractPlanInternal(DbCommand command)
    {
        using var statisticsCommand = command.Connection.CreateCommand();
        try
        {
            statisticsCommand.Transaction = command.Transaction;
            statisticsCommand.CommandText = "SET STATISTICS XML ON";
            statisticsCommand.ExecuteNonQuery();

            using var reader = command.ExecuteReader();
            while (reader.NextResult())
            {
                if (reader.GetName(0) == "Microsoft SQL Server 2005 XML Showplan")
                {
                    reader.Read();
                    return reader.GetString(0);
                }
            }
        }
        finally
        {
            statisticsCommand.CommandText = "SET STATISTICS XML OFF";
            statisticsCommand.ExecuteNonQuery();
        }

        return null;
    }

    internal override string GetPlanDirectory(string baseDirectory) => Path.Combine(baseDirectory, "SqlServer");
}

class PostgresDatabaseProvider(DbCommand command) : DatabaseProvider(command)
{
    protected override string ExtractPlanInternal(DbCommand command)
    {
        command.CommandText = "EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS) " + command.CommandText;

        using var reader = command.ExecuteReader();
        var plan = string.Join(Environment.NewLine, reader.Cast<IDataRecord>().Select(r => r.GetString(0)));

        return plan;
    }

    internal override string GetPlanDirectory(string baseDirectory) => Path.Combine(baseDirectory, "Postgres");
}

class OracleDatabaseProvider(DbCommand command) : DatabaseProvider(command)
{
    protected override string ExtractPlanInternal(DbCommand command)
    {
        command.CommandText = "EXPLAIN PLAN FOR " + command.CommandText;
        command.ExecuteNonQuery();

        // Querying the execution plan using DBMS_XPLAN
        command.CommandText = "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY())";
        using var reader = command.ExecuteReader();

        // Fetching the plan output
        var plan = string.Join(Environment.NewLine, reader.Cast<IDataRecord>().Select(r => r.GetString(0)));

        return plan;
    }

    internal override string GetPlanDirectory(string baseDirectory) => Path.Combine(baseDirectory, "Oracle");
}