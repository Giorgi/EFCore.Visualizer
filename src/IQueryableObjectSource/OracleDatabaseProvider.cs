using System;
using System.Data;
using System.Data.Common;
using System.IO;
using System.Linq;

namespace IQueryableObjectSource;

internal class OracleDatabaseProvider(DbCommand command) : DatabaseProvider(command)
{
    protected override string ExtractPlanInternal(DbCommand command)
    {
        using var statisticsCommand = command.Connection.CreateCommand();
        try
        {
            statisticsCommand.Transaction = command.Transaction;
            statisticsCommand.CommandText = "ALTER SESSION SET statistics_level = ALL";
            statisticsCommand.ExecuteNonQuery();

            // We need to empty the reader stream, so V$SQL_PLAN has all the stats, otherwise when we will query the plan - we will get older plan
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