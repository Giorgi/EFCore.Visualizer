using Community.VisualStudio.Toolkit;

namespace EFCore.Visualizer;

class General : BaseOptionModel<General>, IRatingConfig
{
    public int RatingRequests { get; set; }
}