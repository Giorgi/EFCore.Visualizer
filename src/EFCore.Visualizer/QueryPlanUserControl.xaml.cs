using IQueryableObjectSource;
using Microsoft.VisualStudio.Extensibility.DebuggerVisualizers;
using Microsoft.VisualStudio.PlatformUI;
using Microsoft.Web.WebView2.Core;
using Nerdbank.Streams;
using System.Buffers;
using System.Diagnostics;
using System.Drawing;
using System.Text;
using System.Windows;
using System.Windows.Controls;

namespace EFCore.Visualizer;

public partial class QueryPlanUserControl : UserControl
{
    private readonly VisualizerTarget visualizerTarget;
    private static readonly string AssemblyLocation = Path.GetDirectoryName(typeof(QueryPlanUserControl).Assembly.Location);
    private string? filePath;

    private Color backgroundColor = VSColorTheme.GetThemedColor(ThemedDialogColors.WindowPanelBrushKey);

    public QueryPlanUserControl(VisualizerTarget visualizerTarget)
    {
        this.visualizerTarget = visualizerTarget;
        InitializeComponent();

        Unloaded += QueryPlanUserControlUnloaded;
    }

    private void QueryPlanUserControlUnloaded(object sender, RoutedEventArgs e)
    {
        SafeDeleteFile(filePath);

        Unloaded -= QueryPlanUserControlUnloaded;
    }

#pragma warning disable VSTHRD100 // Avoid async void methods
    protected override async void OnInitialized(EventArgs e)
#pragma warning restore VSTHRD100 // Avoid async void methods
    {
        try
        {
            base.OnInitialized(e);

            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: Path.Combine(AssemblyLocation, "WVData"));
            await webView.EnsureCoreWebView2Async(environment);

            webView.CoreWebView2.Profile.PreferredColorScheme = IsBackgroundDarkColor(backgroundColor) ? CoreWebView2PreferredColorScheme.Dark : CoreWebView2PreferredColorScheme.Light;
#if !DEBUG
            webView.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
            webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
#endif
            (var _, var _, filePath) = await GetQueryAsync();

            var (isError, error, planFilePath) = await GetQueryPlanAsync();

            if (isError && !string.IsNullOrWhiteSpace(error))
            {
                MessageBox.Show(error, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
            else
            {
                SafeDeleteFile(filePath);
                filePath = planFilePath;
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show("Cannot retrieve query plan: " + ex.Message, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            if (!string.IsNullOrEmpty(filePath))
            {
                webView.CoreWebView2.Navigate(filePath);
            }
        }
    }

    private async Task<(bool isError, string error, string data)> GetQueryAsync()
    {
        var message = new ReadOnlySequence<byte>([(byte)OperationType.GetQuery]);
        var response = await visualizerTarget.ObjectSource.RequestDataAsync(message, CancellationToken.None);

        return ReadString(response);
    }

    private async Task<(bool isError, string error, string data)> GetQueryPlanAsync()
    {
        var message = new ReadOnlySequence<byte>([(byte)OperationType.GetQueryPlan]);
        var response = await visualizerTarget.ObjectSource.RequestDataAsync(message, CancellationToken.None);

        return ReadString(response);
    }

    private static (bool isError, string error, string data) ReadString(ReadOnlySequence<byte>? response)
    {
        if (response.HasValue)
        {
            using var stream = response.Value.AsStream();
            using var binaryReader = new BinaryReader(stream, Encoding.Default);
            var isError = binaryReader.ReadBoolean();

            var data = binaryReader.ReadString();
            return isError ? (isError, data, "") : (isError, "", data);
        }

        return (true, string.Empty, string.Empty);
    }

    private void ButtonReviewClick(object sender, RoutedEventArgs e)
    {
        StartProcess("https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.EFCoreVisualizer&ssr=false#review-details");
    }

    private void ButtonSponsorClick(object sender, RoutedEventArgs e)
    {
        StartProcess("https://github.com/sponsors/Giorgi/");
    }

    private void ButtonGitHubClick(object sender, RoutedEventArgs e)
    {
        StartProcess("https://github.com/Giorgi/EFCore.Visualizer");
    }

    private void ButtonCoffeeClick(object sender, RoutedEventArgs e)
    {
        StartProcess("https://ko-fi.com/giorgi");
    }

    private static void StartProcess(string url)
    {
        try
        {
            Process.Start(url);
        }
        catch
        {
            // Ignore
        }
    }

    private static void SafeDeleteFile(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;

        try
        {
            File.Delete(path);
        }
        catch
        {
            // Ignore
        }
    }

    private static bool IsBackgroundDarkColor(Color color) => color.R * 0.2126 + color.G * 0.7152 + color.B * 0.0722 < 255 / 2.0;

    private void WebViewNavigationCompleted(object sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        _ = webView.CoreWebView2.ExecuteScriptAsync($"document.querySelector(':root').style.setProperty('--bg-color', 'RGB({backgroundColor.R}, {backgroundColor.G}, {backgroundColor.B})');");
    }
}