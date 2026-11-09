using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

/// <summary>The model, shared by every database. Each subclass only says which database to use.</summary>
public abstract class BloggingContext : DbContext
{
    public DbSet<Blog> Blogs => Set<Blog>();
    public DbSet<Post> Posts => Set<Post>();

    /// <summary>
    /// The context for <paramref name="database"/>: sqlite, sqlserver, postgres or oracle. Server databases take
    /// their connection string from <c>ConnectionStrings</c> in <paramref name="configuration"/>.
    /// </summary>
    public static BloggingContext Create(string database, IConfiguration configuration) => database.ToLowerInvariant() switch
    {
        "sqlite" => new SqliteBloggingContext(),
        "sqlserver" => new SqlServerBloggingContext(ConnectionString(configuration, "SqlServer")),
        "postgres" => new PostgresBloggingContext(ConnectionString(configuration, "Postgres")),
        "oracle" => new OracleBloggingContext(ConnectionString(configuration, "Oracle")),
        _ => throw new ArgumentException($"Unknown database \"{database}\". Use sqlite, sqlserver, postgres or oracle.", nameof(database)),
    };

    /// <summary>
    /// Creates the database and its sample data if they don't exist yet. The SQLite file is in the output folder,
    /// so it's created fresh on every run. Server databases are kept, so later runs start faster; drop the
    /// database to get a fresh one after the model changes.
    /// </summary>
    public void Prepare()
    {
        if (this is SqliteBloggingContext)
        {
            Database.EnsureDeleted();
        }
        Database.EnsureCreated();
        if (!Blogs.Any())
        {
            SampleData.Add(this);
            SaveChanges();
        }
    }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        // Lengths, so that SQL Server and Oracle can index these columns.
        modelBuilder.Entity<Blog>().Property(b => b.Url).HasMaxLength(200);
        modelBuilder.Entity<Post>().Property(p => p.Title).HasMaxLength(200);
        modelBuilder.Entity<Post>().Property(p => p.Topic).HasMaxLength(50);

        modelBuilder.Entity<Blog>().HasIndex(b => b.Rating);
        modelBuilder.Entity<Post>().HasIndex(p => p.PublishedOn);
        // Its plan names the index "IX_{query}": the visualizer must not treat it as its own placeholder.
        modelBuilder.Entity<Post>().HasIndex(p => p.Title).HasDatabaseName("IX_{query}");
    }

    private static string ConnectionString(IConfiguration configuration, string name) =>
        configuration.GetConnectionString(name) is { Length: > 0 } value
            ? value
            : throw new InvalidOperationException(
                $"No connection string \"{name}\". Set ConnectionStrings:{name} in appsettings.json or user secrets, " +
                $"or the ConnectionStrings__{name} environment variable.");
}

public class SqliteBloggingContext : BloggingContext
{
    protected override void OnConfiguring(DbContextOptionsBuilder options) =>
        options.UseSqlite($"Data Source={Path.Combine(AppContext.BaseDirectory, "blogging.db")}");
}

public class SqlServerBloggingContext(string connectionString) : BloggingContext
{
    protected override void OnConfiguring(DbContextOptionsBuilder options) => options.UseSqlServer(connectionString);
}

public class PostgresBloggingContext(string connectionString) : BloggingContext
{
    protected override void OnConfiguring(DbContextOptionsBuilder options) => options.UseNpgsql(connectionString);

    // The sample's dates have no time zone (Kind=Unspecified). Npgsql maps DateTime to "timestamp with time zone",
    // which accepts only UTC dates, so store them without a time zone, as the other databases do.
    protected override void ConfigureConventions(ModelConfigurationBuilder configurationBuilder) =>
        configurationBuilder.Properties<DateTime>().HaveColumnType("timestamp without time zone");
}

/// <summary>Oracle has no separate databases: the tables go into the schema of the user in the connection string.</summary>
public class OracleBloggingContext(string connectionString) : BloggingContext
{
    protected override void OnConfiguring(DbContextOptionsBuilder options) => options.UseOracle(connectionString);
}
