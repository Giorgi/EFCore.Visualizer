using System.Data;
using System.Data.Common;
using System.Text.Encodings.Web;

namespace IQueryableObjectSource;

internal abstract class DatabaseProvider(DbCommand command)
{
    protected DbCommand Command { get; } = command;

    public string ExtractPlan()
    {
        var needToClose = false;

        try
        {
            if (Command.Connection.State != ConnectionState.Open)
            {
                needToClose = true;
                Command.Connection.Open();
            }

            return ExtractPlanInternal(Command);
        }
        finally
        {
            if (needToClose)
            {
                Command.Connection.Close();
            }
        }
    }

    protected abstract string ExtractPlanInternal(DbCommand command);

    internal abstract string GetPlanDirectory(string baseDirectory);

    /// <summary>
    /// Encodes <paramref name="input"/> for a JavaScript string literal in a template's inline script.
    /// The query and plan can contain data (parameter values, literals), so this also escapes
    /// &lt; (a "&lt;/script&gt;" would end the script), quotes, ` and &amp; as \uXXXX.
    /// </summary>
    public virtual string Encode(string input) => JavaScriptEncoder.Default.Encode(input);
}