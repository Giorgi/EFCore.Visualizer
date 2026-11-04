namespace IQueryableObjectSource;

public enum OperationType : byte
{
    Unknown = 0,
    GetQuery = 1,
    GetQueryPlan = 2,
}