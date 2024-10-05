using System;
using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Encodings.Web;
using System.Threading.Tasks;
using System.Net;
using Microsoft.EntityFrameworkCore.Storage;
using System.Diagnostics;

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
                var operationType = GetOperationType(incomingData);
                switch (operationType)
                {
                    case OperationType.GetQuery:
                        HandleGetQuery(queryable, outgoingData);
                        break;

                    case OperationType.NotSupported:
                        throw new InvalidOperationException("Unknown operation type."); 

                    default:
                        HandleGetQueryPlan(queryable, incomingData, outgoingData);
                        break;
                }
            }
            catch (Exception ex)
            {
                WriteError(outgoingData, ex.Message);
            }
        }
        private void HandleGetQuery(IQueryable queryable, Stream outgoingData)
        {
            using var queryWriter = new BinaryWriter(outgoingData, Encoding.Default, true);
            queryWriter.Write(false); // Indicates no error
            queryWriter.Write(GenerateHtml(queryable.ToQueryString()));
        }
        private void HandleGetQueryPlan(IQueryable queryable, Stream incomingData, Stream outgoingData)
        {
            using var command = queryable.CreateDbCommand();
            var provider = GetDatabaseProvider(command);

            if (provider == null)
            {
                return;
            }

            var query = queryable.ToQueryString();
            var rawPlan = provider.ExtractPlan();

            var (r, g, b) = ReadBackgroundColor(incomingData);
            var isBackgroundDarkColor = r * 0.2126 + g * 0.7152 + b * 0.0722 < 255 / 2.0; 

            var planFile = GeneratePlanFile(provider, query, rawPlan, r, g, b, isBackgroundDarkColor);

            using var writer = new BinaryWriter(outgoingData, Encoding.Default, true);
            writer.Write(false); // Indicates no error
            writer.Write(planFile);
        }
        private (int r, int g, int b) ReadBackgroundColor(Stream incomingData)
        {
            var buffer = new byte[3];
            var r = 255;
            var g = 255;
            var b = 255;

            if (incomingData.Read(buffer, 0, buffer.Length) == buffer.Length)
            {
                r = buffer[0];
                g = buffer[1];
                b = buffer[2];
            }

            return (r, g, b);
        }

        private string GeneratePlanFile(DatabaseProvider provider, string query, string rawPlan, int r, int g, int b, bool isBackgroundDarkColor)
        {
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

        private void WriteError(Stream outgoingData, string errorMessage)
        {
            using var writer = new BinaryWriter(outgoingData, Encoding.Default, true);
            writer.Write(true); // Indicates an error occurred
            writer.Write(errorMessage);
        }

        public static OperationType GetOperationType(Stream stream)
        {
            try
            {
                var operationBuffer = new byte[1];
                stream.Read(operationBuffer, 0, 1);
                return (OperationType)operationBuffer[0];
            }
            catch (Exception)
            {
                return OperationType.NotSupported;
            }
        }

        private static string GenerateHtml(string query)
        {
            string escapedQuery = WebUtility.HtmlEncode(query);
            string templatePath = Path.Combine(ResourcesLocation,"Common", "template.html");
            if (!File.Exists(templatePath))
            {
                throw new FileNotFoundException("Common Query template file not found", templatePath);
            }

            string templateContent = File.ReadAllText(templatePath);
            string finalHtml = templateContent.Replace("{query}", escapedQuery);
            return finalHtml;
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
