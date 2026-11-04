/// <summary>
/// 200 blogs with 0 to 15 posts each (about 1,500 posts), so the plans show real row counts. The data is computed,
/// not random, so every run and every database gets the same rows.
/// </summary>
public static class SampleData
{
    private static readonly string[] Topics = ["EF Core", "LINQ", "SQL Server", "PostgreSQL", "Oracle", "SQLite", "Performance", "Testing"];
    private static readonly DateTime FirstDay = new(2024, 1, 1);

    public static void Add(BloggingContext context)
    {
        for (var b = 1; b <= 200; b++)
        {
            var blog = new Blog { Url = $"https://blog{b:000}.example.com", Rating = 1 + b % 5 };
            for (var p = 1; p <= b % 16; p++)
            {
                var topic = Topics[(b + p) % Topics.Length];
                blog.Posts.Add(new Post
                {
                    Title = $"{topic} notes, part {p}",
                    Topic = topic,
                    PublishedOn = FirstDay.AddDays((b * 7 + p * 13) % 700),
                });
            }
            context.Blogs.Add(blog);
        }
    }
}
