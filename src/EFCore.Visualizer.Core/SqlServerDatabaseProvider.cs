using System.Data.Common;
using System.IO;

namespace IQueryableObjectSource;

internal class SqlServerDatabaseProvider(DbCommand command) : DatabaseProvider(command)
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