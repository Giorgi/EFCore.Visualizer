using Microsoft.EntityFrameworkCore;

using var context = new BloggingContext();
context.Database.EnsureDeleted();
context.Database.EnsureCreated();

context.Blogs.Add(new Blog { Url = "https://example.com", Rating = 5 });
context.SaveChanges();

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

Console.WriteLine(query.Count()); // BREAKPOINT: integration test stops here and inspects `query`

public class BloggingContext : DbContext
{
    public DbSet<Blog> Blogs => Set<Blog>();

    protected override void OnConfiguring(DbContextOptionsBuilder options)
        => options.UseSqlite($"Data Source={Path.Combine(AppContext.BaseDirectory, "blogging.db")}");

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Blog>().HasIndex(b => b.Rating);
        modelBuilder.Entity<Post>().HasIndex(p => p.Title).HasDatabaseName("IX_{query}");
    }
}

public class Blog
{
    public int BlogId { get; set; }
    public string Url { get; set; } = "";
    public int Rating { get; set; }
    public List<Post> Posts { get; } = new();
}

public class Post
{
    public int PostId { get; set; }
    public int BlogId { get; set; }
    public string Title { get; set; } = "";
}
