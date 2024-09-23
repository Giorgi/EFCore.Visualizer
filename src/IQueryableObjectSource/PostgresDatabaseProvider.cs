using System;
using System.Data;
using System.Data.Common;
using System.IO;
using System.Linq;

namespace IQueryableObjectSource;

internal class PostgresDatabaseProvider(DbCommand command) : DatabaseProvider(command)
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