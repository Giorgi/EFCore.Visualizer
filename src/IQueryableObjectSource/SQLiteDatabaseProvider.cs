using System;
using System.Collections.Generic;
using System.Data.Common;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;

namespace IQueryableObjectSource;

internal class SQLiteDatabaseProvider(DbCommand command) : DatabaseProvider(command)
{
    protected override string ExtractPlanInternal(DbCommand command)
    {
        command.CommandText = $"EXPLAIN QUERY PLAN {command.CommandText}";

        var planItems = new List<(int id, int parent, string detail)>
        {
            (0, -1, "Query Plan")  // Add the root "Query Plan" item
        };

        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            var item =
            (
                Convert.ToInt32(reader["id"]),
                Convert.ToInt32(reader["parent"]),
                reader["detail"].ToString()
            );
            planItems.Add(item);
        }

        var htmlBuilder = new StringBuilder();

        BuildIndentedPlanHtml(planItems, -1, htmlBuilder);

        return htmlBuilder.ToString();
    }

    private void BuildIndentedPlanHtml(List<(int id, int parent, string detail)> items, int parentId, StringBuilder builder)
    {
        var matches = items.Where(i => i.parent == parentId).ToList();

        if (matches.Any())
        {
            builder.AppendLine("<ul>");
            foreach (var item in matches)
            {
                builder.AppendLine("<li>");

                builder.AppendLine($"<span class=\"tf-nc\">{WebUtility.HtmlEncode(item.detail)}</span>");

                BuildIndentedPlanHtml(items, item.id, builder);

                builder.AppendLine("</li>");
            }
            builder.AppendLine("</ul>");
        }
    }

    internal override string GetPlanDirectory(string baseDirectory) => Path.Combine(baseDirectory, "SQLite");

    public override string Encode(string input) => input;
}
