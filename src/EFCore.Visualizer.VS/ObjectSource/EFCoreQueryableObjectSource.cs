using Microsoft.VisualStudio.DebuggerVisualizers;
using System;
using System.IO;
using System.Linq;

namespace IQueryableObjectSource;

/// <summary>
/// Visual Studio object source. Thin adapter over <see cref="QueryPlanHelper"/>, which holds
/// all the debugger-agnostic logic and is shared with the VS Code extension.
/// </summary>
public class EFCoreQueryableObjectSource : VisualizerObjectSource
{
    public override void TransferData(object target, Stream incomingData, Stream outgoingData)
    {
        if (target is not IQueryable queryable)
        {
            return;
        }

        try
        {
            var operationType = ReadOperationType(incomingData);
            switch (operationType)
            {
                case OperationType.GetQuery:
                    outgoingData.WriteSuccess(QueryPlanHelper.GetQueryFile(queryable));
                    break;
                case OperationType.GetQueryPlan:
                    outgoingData.WriteSuccess(QueryPlanHelper.GetPlanFile(queryable));
                    break;
                case OperationType.Unknown:
                default:
                    outgoingData.WriteError("Unknown operation type");
                    break;
            }
        }
        catch (Exception ex)
        {
            outgoingData.WriteError(ex.Message);
        }
    }

    private static OperationType ReadOperationType(Stream stream)
    {
        var operationBuffer = new byte[1];
        if (stream.Read(operationBuffer, 0, 1) == operationBuffer.Length)
        {
            if (Enum.IsDefined(typeof(OperationType), operationBuffer[0]))
            {
                return (OperationType)operationBuffer[0];
            }
        }
        return OperationType.Unknown;
    }
}
