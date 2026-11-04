using Microsoft.EntityFrameworkCore;
using System;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace IQueryableObjectSource;

/// <summary>
/// Debugger-agnostic entry points that run inside the debuggee.
/// Debuggers call these through expression evaluation (func-eval), for example:
/// <c>System.Reflection.Assembly.LoadFrom("…/EFCore.Visualizer.DebuggeeHelper.dll")</c> followed by
/// <c>IQueryableObjectSource.QueryPlanHelper.GetPlanFile(query)</c>.
/// Both methods return the full path of an HTML file generated in the temp folder and throw on failure.
/// The caller deletes the file when it no longer needs it.
/// </summary>
public static class QueryPlanHelper
{
    /// <summary>
    /// Folder that contains the template folders (Common, SqlServer, Postgres, Oracle, SQLite):
    /// <c>../Resources</c> relative to this assembly.
    /// </summary>
    private static readonly string ResourcesLocation = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(typeof(QueryPlanHelper).Assembly.Location)), "Resources");

    /// <summary>Generates an HTML page with the SQL of <paramref name="query"/> and returns its path.</summary>
    public static string GetQueryFile(object query) => GenerateQueryFile(AsQueryable(query).ToQueryString());

    /// <summary>
    /// SHA-256 (hex) of the SQL of <paramref name="query"/>, parameter values included. Debuggers compare it to tell
    /// whether a query changed since its plan was shown. Generating the SQL doesn't run the query.
    /// </summary>
    public static string GetQueryFingerprint(object query) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(AsQueryable(query).ToQueryString())));

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
        var encodedPlan = provider.Encode(rawPlan);
        var encodedQuery = provider.Encode(query);

        // One pass, so a "{query}" inside the plan text (a literal, a table name) stays as it is
        // instead of getting the query put into it.
        var planPageHtml = Placeholder.Replace(ReadTemplate(provider.GetPlanDirectory(ResourcesLocation)),
            match => match.Value == "{plan}" ? encodedPlan : encodedQuery);

        return WritePage(planPageHtml);
    }

    private static string GenerateQueryFile(string query)
    {
        var queryDirectory = Path.Combine(ResourcesLocation, "Common");
        var templatePath = Path.Combine(queryDirectory, "template.html");
        if (!File.Exists(templatePath))
        {
            throw new FileNotFoundException("Common Query template file not found", templatePath);
        }

        var finalHtml = ReadTemplate(queryDirectory).Replace("{query}", WebUtility.HtmlEncode(query));

        return WritePage(finalHtml);
    }

    /// <summary>Writes the page to a new file in the temp folder and returns its path.</summary>
    private static string WritePage(string html)
    {
        var pageFile = Path.Combine(Path.GetTempPath(), "EFCore.Visualizer-" + Path.ChangeExtension(Path.GetRandomFileName(), "html"));

        // CreateNew: never write through a file or link that already exists under this name.
        using var stream = new FileStream(pageFile, FileMode.CreateNew, FileAccess.Write);
        using var writer = new StreamWriter(stream);
        writer.Write(html);

        return pageFile;
    }

    private static readonly Regex Placeholder = new(@"\{plan\}|\{query\}");

    // <script src="..."> and <link href="..."> in a template.
    private static readonly Regex AssetLink = new(@"(<(?:script|link)\b[^>]*?\b(?:src|href)\s*=\s*"")([^""]*)("")", RegexOptions.IgnoreCase);

    /// <summary>
    /// Reads <c>template.html</c> from <paramref name="templateDirectory"/>. The page is written to the temp folder,
    /// so the template's relative links to its scripts and styles ("qp.css", "../Common/highlight.min.js",
    /// "..\Common\treeflex.css") are made absolute file: URLs. A &lt;base&gt; would do the same, but it would also
    /// redirect the page's own "#..." links (pev2's tabs) to the template folder.
    /// This runs on the template only, before any query or plan text goes in.
    /// </summary>
    private static string ReadTemplate(string templateDirectory)
    {
        var template = File.ReadAllText(Path.Combine(templateDirectory, "template.html"));

        return AssetLink.Replace(template, match =>
        {
            var link = match.Groups[2].Value;
            if (link.Length == 0 || link.StartsWith("#") || link.StartsWith("/") || link.StartsWith("\\") || link.Contains(":"))
            {
                return match.Value; // not relative to the template folder
            }

            // AbsoluteUri already percent-encodes '"', '<', '>' and spaces, so only '&' needs escaping in the attribute.
            // Don't HtmlEncode it: that would turn the "'" of a path like C:\Users\O'Brien into "&#39;", which a
            // reader that doesn't decode every HTML entity (the VS Code webview) would take for a URL fragment.
            var file = Path.GetFullPath(Path.Combine(templateDirectory, link.Replace('\\', '/')));
            return match.Groups[1].Value + new Uri(file).AbsoluteUri.Replace("&", "&amp;") + match.Groups[3].Value;
        });
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
