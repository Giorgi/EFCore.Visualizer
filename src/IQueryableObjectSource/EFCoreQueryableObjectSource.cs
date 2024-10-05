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
                var dbOperation = ConvertStreamToString(incomingData);
                switch (dbOperation)
                {
                    case "GetQuery":
                        HandleGetQuery(queryable, outgoingData);
                        break;

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

        public static string ConvertStreamToString(Stream stream)
        {
            using (MemoryStream memoryStream = new MemoryStream())
            {
                stream.CopyTo(memoryStream);
                byte[] byteArray = memoryStream.ToArray();

                //Try to convert byte array to a string using UTF-8 encoding
                try
                {
                    string result = Encoding.UTF8.GetString(byteArray);
                    return result;
                }
                catch (Exception)
                {
                    return "GetQueryPlan";
                }
            }
        }

        private static string GenerateHtml(string query)
        {
            string escapedQuery = WebUtility.HtmlEncode(query);

            // Simple HTML structure to display the query
            StringBuilder htmlBuilder = new StringBuilder();
            htmlBuilder.AppendLine("<html>");
            htmlBuilder.AppendLine("<head><title>Query Plan Visualizer</title>");
            htmlBuilder.AppendLine("<style>");
            htmlBuilder.AppendLine("body { font-family: Arial, sans-serif; margin: 20px; }");
            htmlBuilder.AppendLine("h2 { color: #333; }");
            htmlBuilder.AppendLine(".query-box { background-color: #f4f4f4; padding: 10px; border-radius: 5px; border: 1px solid #ccc; overflow-x: auto; max-width: 100%; }");
            htmlBuilder.AppendLine("pre { white-space: pre-wrap; word-wrap: break-word; }");
            htmlBuilder.AppendLine("</style>");
            htmlBuilder.AppendLine("</head>");
            htmlBuilder.AppendLine("<body>");
            htmlBuilder.AppendLine("<h2>SQL Query</h2>");
            htmlBuilder.AppendLine("<div class='query-box'>");
            htmlBuilder.AppendLine("<pre>" + escapedQuery + "</pre>");
            htmlBuilder.AppendLine("</div>");
            htmlBuilder.AppendLine("</body>");
            htmlBuilder.AppendLine("</html>");

            return htmlBuilder.ToString();
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
