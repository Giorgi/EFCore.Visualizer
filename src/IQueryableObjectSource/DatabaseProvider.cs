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

    public virtual string Encode(string input) => JavaScriptEncoder.UnsafeRelaxedJsonEscaping.Encode(input).Replace("'", "\\'");
}