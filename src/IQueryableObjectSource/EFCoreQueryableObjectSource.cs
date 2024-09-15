using System;
using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Text;
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
                using var command = queryable.CreateDbCommand();
                var provider = GetDatabaseProvider(command);

                if (provider == null)
                {
                    return;
                }

                var query = queryable.ToQueryString();
                var rawPlan = provider.ExtractPlan();

                var buffer = new byte[3];
                var isBackgroundDarkColor = false;

                var r = 255;
                var g = 255;
                var b = 255;

                if (incomingData.Read(buffer, 0, buffer.Length) == buffer.Length)
                {
                    r = buffer[0];
                    g = buffer[1];
                    b = buffer[2];
                }

                isBackgroundDarkColor = r * 0.2126 + g * 0.7152 + b * 0.0722 < 255 / 2.0;

                var planFile = Path.Combine(provider.GetPlanDirectory(ResourcesLocation), Path.ChangeExtension(Path.GetRandomFileName(), "html"));

                var planPageHtml = File.ReadAllText(Path.Combine(provider.GetPlanDirectory(ResourcesLocation), "template.html"))
                    .Replace("{backColor}", $"rgb({r} {g} {b})")
                    .Replace("{textColor}", isBackgroundDarkColor ? "white" : "black")
                    .Replace("{plan}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(rawPlan).Replace("'", "\\'"))
                    .Replace("{query}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(query).Replace("'", "\\'"));

                File.WriteAllText(planFile, planPageHtml);

                using var writer = new BinaryWriter(outgoingData, Encoding.Default, true);
                writer.Write(false);
                writer.Write(planFile);
            }
            catch (Exception ex)
            {
                using var writer = new BinaryWriter(outgoingData, Encoding.Default, true);
                writer.Write(true);
                writer.Write(ex.Message);
            }
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
