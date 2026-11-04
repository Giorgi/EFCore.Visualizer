using System.IO;
using System.Text;

namespace IQueryableObjectSource;

// The reply is read by the VS extension (QueryPlanUserControl.ReadString), which runs on .NET Framework.
// Both sides name UTF-8: Encoding.Default is UTF-8 here (.NET) but the ANSI code page there, which would
// garble non-ASCII paths and error messages.
static class StreamExtensions
{
    public static void WriteError(this Stream outgoingData, string errorMessage)
    {
        using var writer = new BinaryWriter(outgoingData, Encoding.UTF8, true);
        writer.Write(true); // Indicates an error occurred
        writer.Write(errorMessage);
    }

    public static void WriteSuccess(this Stream outgoingData, string data)
    {
        using var writer = new BinaryWriter(outgoingData, Encoding.UTF8, true);
        writer.Write(false); // Indicates an error occurred
        writer.Write(data);
    }
}