using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Net;

namespace IQueryableObjectSource;

public class EFCoreQueryableObjectSource : VisualizerObjectSource
{
    private static readonly string ResourcesLocation = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(typeof(EFCoreQueryableObjectSource).Assembly.Location)), "Resources");

    public override void TransferData(object target, Stream incomingData, Stream outgoingData)
    {
        if (target is not IQueryable queryable)
        {
            return;
        }

        try
        {
            var operationType = ReadOperationType(incomingData);
            switch (operationType)
            {
                case OperationType.GetQuery:
                    GetQuery(queryable, outgoingData);
                    break;
                case OperationType.GetQueryPlan:
                    GetQueryPlan(queryable, outgoingData);
                    break;
                case OperationType.Unknown:
                default:
                    outgoingData.WriteError("Unknown operation type");
                    break;
            }
        }
        catch (Exception ex)
        {
            outgoingData.WriteError(ex.Message);
        }
    }

    private static void GetQuery(IQueryable queryable, Stream outgoingData)
    {
        var html = GenerateQueryFile(queryable.ToQueryString());
        outgoingData.WriteSuccess(html);
    }

    private static void GetQueryPlan(IQueryable queryable, Stream outgoingData)
    {
        using var command = queryable.CreateDbCommand();
        var provider = GetDatabaseProvider(command);

        if (provider == null)
        {
            outgoingData.WriteError($"Unsupported database provider {command.GetType().FullName}");
            return;
        }

        try
        {
            var query = queryable.ToQueryString();
            var rawPlan = provider.ExtractPlan();

            var planFile = GeneratePlanFile(provider, query, rawPlan);

            outgoingData.WriteSuccess(planFile);
        }
        catch (Exception ex)
        {
            outgoingData.WriteError($"Failed to extract execution plan. {ex.Message}");
        }
    }

    private static string GeneratePlanFile(DatabaseProvider provider, string query, string rawPlan)
    {
        var planDirectory = provider.GetPlanDirectory(ResourcesLocation);
        var planFile = Path.Combine(planDirectory, Path.ChangeExtension(Path.GetRandomFileName(), "html"));

        var planPageHtml = File.ReadAllText(Path.Combine(planDirectory, "template.html"))
            .Replace("{plan}", provider.Encode(rawPlan))
            .Replace("{query}", provider.Encode(query));

        File.WriteAllText(planFile, planPageHtml);

        return planFile;
    }

    private static string GenerateQueryFile(string query)
    {
        var templatePath = Path.Combine(ResourcesLocation, "Common", "template.html");
        if (!File.Exists(templatePath))
        {
            throw new FileNotFoundException("Common Query template file not found", templatePath);
        }

        var queryDirectory = Path.Combine(ResourcesLocation, "Common");
        var queryFile = Path.Combine(queryDirectory, Path.ChangeExtension(Path.GetRandomFileName(), "html"));

        var templateContent = File.ReadAllText(templatePath);

        var finalHtml = templateContent.Replace("{query}", WebUtility.HtmlEncode(query));

        File.WriteAllText(queryFile, finalHtml);

        return queryFile;
    }

    private static OperationType ReadOperationType(Stream stream)
    {
        var operationBuffer = new byte[1];
        if (stream.Read(operationBuffer, 0, 1) == operationBuffer.Length)
        {
            if (Enum.IsDefined(typeof(OperationType), operationBuffer[0]))
            {
                return (OperationType)operationBuffer[0];
            }
        }
        return OperationType.Unknown;
    }

    private static DatabaseProvider GetDatabaseProvider(DbCommand command)
    {
        return command.GetType().FullName switch
        {
            "Microsoft.Data.SqlClient.SqlCommand" => new SqlServerDatabaseProvider(command),
            "Npgsql.NpgsqlCommand" => new PostgresDatabaseProvider(command),
            "Oracle.ManagedDataAccess.Client.OracleCommand" => new OracleDatabaseProvider(command),
            "Microsoft.Data.Sqlite.SqliteCommand" => new SQLiteDatabaseProvider(command),
            _ => null
        };
    }
}