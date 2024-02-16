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