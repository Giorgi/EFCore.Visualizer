﻿using Microsoft.VisualStudio.Extensibility.DebuggerVisualizers;
using Microsoft.VisualStudio.PlatformUI;
using Microsoft.Web.WebView2.Core;
using Nerdbank.Streams;
using System.Buffers;
using System.Diagnostics;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using IQueryableObjectSource;

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
            var query = string.Empty;
            try
            {
                base.OnInitialized(e);


                var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: Path.Combine(AssemblyLocation, "WVData"));
                await webView.EnsureCoreWebView2Async(environment);

                query = await GetQueryAsync();

#if !DEBUG
                webView.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
                webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false; 
#endif
                var color = VSColorTheme.GetThemedColor(ThemedDialogColors.WindowPanelBrushKey);

                var response = await visualizerTarget.ObjectSource.RequestDataAsync(new ReadOnlySequence<byte>([color.R, color.G, color.B]), CancellationToken.None);

                if (!response.HasValue)
                {
                    return;
                }

                using var stream = response.Value.AsStream();
                using var binaryReader = new BinaryReader(stream, Encoding.Default);

                var isError = binaryReader.ReadBoolean();

                if (isError)
                {
                    MessageBox.Show(binaryReader.ReadString(), "Error", MessageBoxButton.OK, MessageBoxImage.Error);
                }
                else
                {
                    planFilePath = binaryReader.ReadString();

                    if (!string.IsNullOrEmpty(planFilePath))
                    {
                        webView.CoreWebView2.Navigate(planFilePath);
                    }
                }
            }
            catch (Exception ex)
            {
                if (!string.IsNullOrEmpty(query))
                    webView.CoreWebView2.NavigateToString(query);
                MessageBox.Show(ex.Message, "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private async Task<string> GetQueryAsync()
        {
            var query = string.Empty;
            var response = await visualizerTarget.ObjectSource.RequestDataAsync(ConvertEnumToReadOnlySequence(OperationType.GetQuery),CancellationToken.None);
            if (response.HasValue)
            {
                using var stream = response.Value.AsStream();
                using var binaryReader = new BinaryReader(stream, Encoding.Default);
                var isError = binaryReader.ReadBoolean();
                if (!isError)
                {
                    query = binaryReader.ReadString();
                }
            }
            return query;
        }
        private ReadOnlySequence<byte> ConvertEnumToReadOnlySequence(OperationType operation)
        {
            var operationByte = new byte[] { (byte)operation };
            return new ReadOnlySequence<byte>(operationByte);
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
