using IQueryableObjectSource;
using Microsoft.VisualStudio.Extensibility.DebuggerVisualizers;
using Microsoft.Web.WebView2.Core;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;

namespace EFCore.Visualizer
{
    public partial class QueryPlanUserControl : UserControl
    {
        private readonly VisualizerTarget visualizerTarget;
        private static readonly string AssemblyLocation = Path.GetDirectoryName(typeof(QueryPlanUserControl).Assembly.Location);
        private string? planFilePath;

        public QueryPlanUserControl(VisualizerTarget visualizerTarget)
        {
            this.visualizerTarget = visualizerTarget;
            InitializeComponent();

            Unloaded += QueryPlanUserControlUnloaded;
        }

        private void QueryPlanUserControlUnloaded(object sender, RoutedEventArgs e)
        {
            try
            {
                File.Delete(planFilePath);
            }
            catch
            {
                // Ignore
            }

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

                var queryInfo = await visualizerTarget.ObjectSource.RequestDataAsync<QueryInfo>(jsonSerializer: null, CancellationToken.None);

                if (string.IsNullOrEmpty(queryInfo.ErrorMessage))
                {
                    planFilePath = queryInfo.PlanLocation;

                    if (!string.IsNullOrEmpty(planFilePath))
                    {
                        webView.CoreWebView2.Navigate(planFilePath);
                    }
                }
                else
                {
                    MessageBox.Show(queryInfo.ErrorMessage, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
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
    }
}
