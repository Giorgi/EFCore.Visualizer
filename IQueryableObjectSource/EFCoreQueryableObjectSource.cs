using Microsoft.EntityFrameworkCore;
using Microsoft.VisualStudio.DebuggerVisualizers;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Text.Encodings.Web;

namespace IQueryableObjectSource
{
    public class EFCoreQueryableObjectSource : VisualizerObjectSource
    {
        private static readonly string ResourcesLocation = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(typeof(EFCoreQueryableObjectSource).Assembly.Location)), "Resources");
        
        public override void GetData(object target, Stream outgoingData)
        {
            if (target is not IQueryable queryable)
            {
                SerializeAsJson(outgoingData, null!);
                return;
            }

            using var command = queryable.CreateDbCommand();
            var provider = GetDatabaseProvider(command);

            if (provider == null)
            {
                SerializeAsJson(outgoingData, null!);
                return;
            }

            var query = queryable.ToQueryString();
            var rawPlan = provider.ExtractPlan();

            var planFile = Path.Combine(provider.GetPlanDirectory(ResourcesLocation), Path.ChangeExtension(Path.GetRandomFileName(), "html"));

            var planPageHtml = File.ReadAllText(Path.Combine(provider.GetPlanDirectory(ResourcesLocation), "template.html"))
                .Replace("{plan}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(rawPlan).Replace("'", "\\'"))
                .Replace("{query}", JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(query).Replace("'", "\\'"));

            File.WriteAllText(planFile, planPageHtml);

            SerializeAsJson(outgoingData, new QueryInfo { PlanHtml = planFile });
        }

        private static DatabaseProvider GetDatabaseProvider(DbCommand command)
        {
            return command.GetType().FullName switch
            {
                "Microsoft.Data.SqlClient.SqlCommand" => new SqlServerDatabaseProvider(command),
                "Npgsql.NpgsqlCommand" => new PostgresDatabaseProvider(command),
                _ => null
            };
        }
    }
}
