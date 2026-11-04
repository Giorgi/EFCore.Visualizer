using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

// Settings: appsettings.json, then user secrets, then environment variables (ConnectionStrings__Postgres, Database).
var configuration = new ConfigurationBuilder()
    .SetBasePath(AppContext.BaseDirectory)
    .AddJsonFile("appsettings.json", optional: false)
    .AddUserSecrets(typeof(BloggingContext).Assembly, optional: true)
    .AddEnvironmentVariables()
    .Build();

// The database: the first argument, else the "Database" setting. sqlite | sqlserver | postgres | oracle.
var database = args.FirstOrDefault() ?? configuration["Database"] ?? "sqlite";

using var context = BloggingContext.Create(database, configuration);
context.Prepare();

// Queries that show what the plan pages look like on real data.
var since = new DateTime(2025, 6, 1);
// A join, with a filter on both tables and a sort.
var recentPosts = context.Posts
    .Where(p => p.Blog.Rating >= 4 && p.PublishedOn >= since)
    .OrderByDescending(p => p.PublishedOn)
    .Select(p => new { p.Title, p.PublishedOn, BlogUrl = p.Blog.Url });
// An aggregate.
var postsPerTopic = context.Posts
    .GroupBy(p => p.Topic)
    .Select(g => new { Topic = g.Key, Posts = g.Count(), Latest = g.Max(p => p.PublishedOn) })
    .OrderByDescending(t => t.Posts);
// A correlated subquery.
var activeBlogs = context.Blogs
    .Where(b => b.Posts.Count(p => p.PublishedOn >= since) >= 3)
    .OrderBy(b => b.Url);

// The cases the VS Code extension's integration tests check. Keep their names and meaning.
var query = context.Blogs.Where(b => b.Rating > 3).OrderBy(b => b.Url);
// More variable shapes, to check which ones get the Visualize Query Plan menu item.
var blogs = context.Blogs;
var included = context.Blogs.Include(b => b.Posts);
IEnumerable<Blog> enumerable = query;
var numbers = new[] { 1, 2, 3 }.AsQueryable();
var list = query.ToList();
// Uses a captured variable, which EF sends as a parameter: changing minRating changes the query's SQL.
var minRating = 3;
var parameterized = context.Blogs.Where(b => b.Rating > minRating);
// Its SQL can be generated, but the plan fails (the table doesn't exist): the visualizer falls back to the SQL.
var broken = context.Database.SqlQueryRaw<int>("SELECT Value FROM MissingTable");
// Its plan names the index "IX_{query}": the placeholder text in the plan must stay as it is.
var postsByTitle = context.Set<Post>().Where(p => p.Title == "x");

Console.WriteLine($"{database}: {query.Count()} blogs rated above 3"); // BREAKPOINT: integration test stops here and inspects `query`
