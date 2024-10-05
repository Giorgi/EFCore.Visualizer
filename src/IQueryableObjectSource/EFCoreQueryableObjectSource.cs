using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.Encodings.Web;

namespace IQueryableObjectSource
{
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

        private static void GetQuery(IQueryable queryable, Stream outgoingData)
        {
            var html = GenerateQueryHtml(queryable.ToQueryString());
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
            var (r, g, b) = ReadBackgroundColor(incomingData);

            var isBackgroundDarkColor = r * 0.2126 + g * 0.7152 + b * 0.0722 < 255 / 2.0;

            var planDirectory = provider.GetPlanDirectory(ResourcesLocation);
            var planFile = Path.Combine(planDirectory, Path.ChangeExtension(Path.GetRandomFileName(), "html"));

            var planPageHtml = File.ReadAllText(Path.Combine(planDirectory, "template.html"))
                .Replace("{backColor}", $"rgb({r} {g} {b})")
                .Replace("{textColor}", isBackgroundDarkColor ? "white" : "black")
                .Replace("{plan}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(rawPlan).Replace("'", "\\'"))
                .Replace("{query}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(query).Replace("'", "\\'"));

            File.WriteAllText(planFile, planPageHtml);

            return planFile;
        }

        private static string GenerateQueryHtml(string query)
        {
            var escapedQuery = WebUtility.HtmlEncode(query);
            var templatePath = Path.Combine(ResourcesLocation, "Common", "template.html");
            if (!File.Exists(templatePath))
            {
                throw new FileNotFoundException("Common Query template file not found", templatePath);
            }

            var templateContent = File.ReadAllText(templatePath);
            var finalHtml = templateContent.Replace("{query}", escapedQuery);
            return finalHtml;
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

        private static (int r, int g, int b) ReadBackgroundColor(Stream incomingData)
        {
            var buffer = new byte[3];

            if (incomingData.Read(buffer, 0, buffer.Length) == buffer.Length)
            {
                return (buffer[0], buffer[1], buffer[2]);
            }

            return (255, 255, 255);
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
}
