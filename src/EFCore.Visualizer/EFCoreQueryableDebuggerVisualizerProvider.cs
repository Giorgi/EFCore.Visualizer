using Microsoft.VisualStudio.Extensibility.DebuggerVisualizers;
using Microsoft.VisualStudio.Extensibility;
using Microsoft.VisualStudio.RpcContracts.RemoteUI;
using Microsoft.VisualStudio.Extensibility.VSSdkCompatibility;
using Microsoft.VisualStudio.Shell;

namespace EFCore.Visualizer
{
    /// <summary>
    /// Debugger visualizer provider class for <see cref="System.String"/>.
    /// </summary>
    [VisualStudioContribution]
    internal class EFCoreQueryableDebuggerVisualizerProvider : DebuggerVisualizerProvider
    {
        private const string EntityQueryable = "Microsoft.EntityFrameworkCore.Query.Internal.EntityQueryable`1, Microsoft.EntityFrameworkCore, Version=0.0.0.0, Culture=neutral";
        private const string IncludableQueryable = "Microsoft.EntityFrameworkCore.EntityFrameworkQueryableExtensions+IncludableQueryable`2, Microsoft.EntityFrameworkCore, Version=0.0.0.0, Culture=neutral";
        private const string DisplayName = "EFCore.Visualizer.DisplayName";

        /// <summary>
        /// Initializes a new instance of the <see cref="EFCoreQueryableDebuggerVisualizerProvider"/> class.
        /// </summary>
        /// <param name="extension">Extension instance.</param>
        /// <param name="extensibility">Extensibility object.</param>
        public EFCoreQueryableDebuggerVisualizerProvider(ExtensionEntrypoint extension, VisualStudioExtensibility extensibility)
            : base(extension, extensibility)
        {
        }

        /// <inheritdoc/>
        public override DebuggerVisualizerProviderConfiguration DebuggerVisualizerProviderConfiguration => new(
                        new VisualizerTargetType($"%{DisplayName}%", EntityQueryable),
                        new VisualizerTargetType($"%{DisplayName}%", IncludableQueryable))
        {
            VisualizerObjectSourceType = new("IQueryableObjectSource.EFCoreQueryableObjectSource, IQueryableObjectSource"),
        };

        /// <inheritdoc/>
        public override async Task<IRemoteUserControl> CreateVisualizerAsync(VisualizerTarget visualizerTarget, CancellationToken cancellationToken)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync(cancellationToken);
            var wrapper = new WpfControlWrapper(new QueryPlanUserControl(visualizerTarget));
            return wrapper;
        }
    }
}
