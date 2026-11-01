using Microsoft.EntityFrameworkCore;
using System;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Net;

namespace IQueryableObjectSource;

/// <summary>
/// Debugger-agnostic entry points that run inside the debuggee.
/// Debuggers call these through expression evaluation (func-eval), for example:
/// <c>System.Reflection.Assembly.LoadFrom("…/EFCore.Visualizer.DebuggeeHelper.dll")</c> followed by
/// <c>IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)</c>.
/// Both methods return the full path of a generated HTML file and throw on failure.
/// </summary>
public static class QueryPlanHelper
{
    private static string resourcesLocation = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(typeof(QueryPlanHelper).Assembly.Location)), "Resources");

    /// <summary>
    /// Folder that contains the Common/SqlServer/Postgres/Oracle/SQLite template folders.
    /// Defaults to <c>../Resources</c> relative to this assembly.
    /// </summary>
    public static string ResourcesLocation
    {
        get => resourcesLocation;
        set => resourcesLocation = value;
    }

    /// <summary>Generates an HTML page with the SQL of <paramref name="query"/> and returns its path.</summary>
    public static string GetQueryFile(object query) => GenerateQueryFile(AsQueryable(query).ToQueryString());

    /// <summary>Generates an HTML page with the execution plan of <paramref name="query"/> and returns its path.</summary>
    public static string GetPlanFile(object query)
    {
        var queryable = AsQueryable(query);

        using var command = queryable.CreateDbCommand();
        var provider = GetDatabaseProvider(command) ?? throw new NotSupportedException($"Unsupported database provider {command.GetType().FullName}");

        try
        {
            var queryString = queryable.ToQueryString();
            var rawPlan = provider.ExtractPlan();

            return GeneratePlanFile(provider, queryString, rawPlan);
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException($"Failed to extract execution plan. {ex.Message}", ex);
        }
    }

    private static IQueryable AsQueryable(object query) =>
        query as IQueryable ?? throw new ArgumentException($"Expected an IQueryable but got {query?.GetType().FullName ?? "null"}", nameof(query));

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

        var finalHtml = File.ReadAllText(templatePath).Replace("{query}", WebUtility.HtmlEncode(query));

        File.WriteAllText(queryFile, finalHtml);

        return queryFile;
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
