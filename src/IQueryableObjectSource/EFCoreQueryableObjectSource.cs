using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System;
using System.Data.Common;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.Encodings.Web;

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
                    GetQuery(queryable, incomingData, outgoingData);
                    break;
                case OperationType.GetQueryPlan:
                    GetQueryPlan(queryable, incomingData, outgoingData);
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

    private static void GetQuery(IQueryable queryable, Stream incomingData, Stream outgoingData)
    {
        var html = GenerateQueryFile(queryable.ToQueryString(), incomingData);
        outgoingData.WriteSuccess(html);
    }

    private static void GetQueryPlan(IQueryable queryable, Stream incomingData, Stream outgoingData)
    {
        using var command = queryable.CreateDbCommand();
        var provider = GetDatabaseProvider(command);

        if (provider == null)
        {
            return;
        }
        
        var query = queryable.ToQueryString();
        var rawPlan = provider.ExtractPlan();

        var planFile = GeneratePlanFile(provider, query, rawPlan, incomingData);

        outgoingData.WriteSuccess(planFile);
    }

    private static string GeneratePlanFile(DatabaseProvider provider, string query, string rawPlan, Stream incomingData)
    {
        var color = ReadBackgroundColor(incomingData);

        var isBackgroundDarkColor = IsBackgroundDarkColor(color);

        var planDirectory = provider.GetPlanDirectory(ResourcesLocation);
        var planFile = Path.Combine(planDirectory, Path.ChangeExtension(Path.GetRandomFileName(), "html"));

        var planPageHtml = File.ReadAllText(Path.Combine(planDirectory, "template.html"))
            .Replace("{backColor}", $"rgb({color.R} {color.G} {color.B})")
            .Replace("{textColor}", isBackgroundDarkColor ? "white" : "black")
            .Replace("{plan}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(rawPlan).Replace("'", "\\'"))
            .Replace("{query}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(query).Replace("'", "\\'"));

        File.WriteAllText(planFile, planPageHtml);

        return planFile;
    }

    private static string GenerateQueryFile(string query, Stream incomingData)
    {
        var color = ReadBackgroundColor(incomingData);

        var isBackgroundDarkColor = IsBackgroundDarkColor(color);

        var templatePath = Path.Combine(ResourcesLocation, "Common", "template.html");
        if (!File.Exists(templatePath))
        {
            throw new FileNotFoundException("Common Query template file not found", templatePath);
        }

        var queryDirectory = Path.Combine(ResourcesLocation, "Common");
        var queryFile = Path.Combine(queryDirectory, Path.ChangeExtension(Path.GetRandomFileName(), "html"));

        var templateContent = File.ReadAllText(templatePath);

        var finalHtml = templateContent.Replace("{query}", WebUtility.HtmlEncode(query))
            .Replace("{backColor}", $"rgb({color.R} {color.G} {color.B})")
            .Replace("{textColor}", isBackgroundDarkColor ? "white" : "black");

        File.WriteAllText(queryFile, finalHtml);

        return queryFile;
    }

    private static bool IsBackgroundDarkColor(Color color) => color.R * 0.2126 + color.G * 0.7152 + color.B * 0.0722 < 255 / 2.0;

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

    private static Color ReadBackgroundColor(Stream incomingData)
    {
        var buffer = new byte[3];

        if (incomingData.Read(buffer, 0, buffer.Length) == buffer.Length)
        {
            return Color.FromArgb(buffer[0], buffer[1], buffer[2]);
        }

        return Color.White;
    }


    private static DatabaseProvider GetDatabaseProvider(DbCommand command)
    {
        return command.GetType().FullName switch
        {
            "Microsoft.Data.SqlClient.SqlCommand" => new SqlServerDatabaseProvider(command),
            "Npgsql.NpgsqlCommand" => new PostgresDatabaseProvider(command),
            "Oracle.ManagedDataAccess.Client.OracleCommand" => new OracleDatabaseProvider(command),
            _ => null
        };
    }
}