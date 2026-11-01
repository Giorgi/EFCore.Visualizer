using Microsoft.EntityFrameworkCore;

using var context = new BloggingContext();
context.Database.EnsureDeleted();
context.Database.EnsureCreated();

context.Blogs.Add(new Blog { Url = "https://example.com", Rating = 5 });
context.SaveChanges();

var query = context.Blogs.Where(b => b.Rating > 3).OrderBy(b => b.Url);

Console.WriteLine(query.Count()); // BREAKPOINT: integration test stops here and inspects `query`

public class BloggingContext : DbContext
{
    public DbSet<Blog> Blogs => Set<Blog>();

    protected override void OnConfiguring(DbContextOptionsBuilder options)
        => options.UseSqlite($"Data Source={Path.Combine(AppContext.BaseDirectory, "blogging.db")}");

    protected override void OnModelCreating(ModelBuilder modelBuilder)
        => modelBuilder.Entity<Blog>().HasIndex(b => b.Rating);
}

public class Blog
{
    public int BlogId { get; set; }
    public string Url { get; set; } = "";
    public int Rating { get; set; }
}
