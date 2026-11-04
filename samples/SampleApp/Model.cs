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
    public Blog Blog { get; set; } = null!;
    public string Title { get; set; } = "";
    public string Topic { get; set; } = "";
    public DateTime PublishedOn { get; set; }
}
