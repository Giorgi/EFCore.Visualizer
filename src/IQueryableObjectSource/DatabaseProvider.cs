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
        using var statisticsCommand = command.Connection.CreateCommand();
        try
        {
            statisticsCommand.Transaction = command.Transaction;
            statisticsCommand.CommandText = "ALTER SESSION SET statistics_level = ALL";
            statisticsCommand.ExecuteNonQuery();

            // We need empty the reader stream, so V$SQL_PLAN has all the stats, otherwise when we will query the plan - we will get older plan
            using var res = command.ExecuteReader();
            while (res.Read()) { };

            statisticsCommand.CommandText = @"SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(format=>'ALLSTATS LAST +cost +bytes +outline +PEEKED_BINDS +PROJECTION +ALIAS'))";
            using var reader = statisticsCommand.ExecuteReader();

            // Fetching the plan output
            return string.Join(Environment.NewLine, reader.Cast<IDataRecord>().Select(r => r.GetString(0)));

        }
        finally
        {
            statisticsCommand.CommandText = "ALTER SESSION SET statistics_level = TYPICAL";
            statisticsCommand.ExecuteNonQuery();
        }
    }

    internal override string GetPlanDirectory(string baseDirectory) => Path.Combine(baseDirectory, "Oracle");
}