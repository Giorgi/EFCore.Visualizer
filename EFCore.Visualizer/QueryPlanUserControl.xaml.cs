using System.Windows;
using System.Windows.Controls;
using IQueryableObjectSource;
using Microsoft.VisualStudio.Extensibility.DebuggerVisualizers;
using Microsoft.Web.WebView2.Core;

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
            catch (Exception exception)
            {
            }

            Unloaded -= QueryPlanUserControlUnloaded;
        }

        protected override async void OnInitialized(EventArgs e)
        {
            base.OnInitialized(e);

            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: Path.Combine(AssemblyLocation, "WVData"));
            await webView.EnsureCoreWebView2Async(environment);

            var queryInfo = await visualizerTarget.ObjectSource.RequestDataAsync<QueryInfo>(jsonSerializer: null, CancellationToken.None);
            planFilePath = queryInfo.PlanHtml;

            if (!string.IsNullOrEmpty(planFilePath))
            {
                webView.CoreWebView2.Navigate(planFilePath);
            }
        }
    }
}
