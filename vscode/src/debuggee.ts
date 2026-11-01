import * as vscode from 'vscode';

/**
 * Talks to the debuggee through plain DAP `evaluate` requests (func-eval), so it works with
 * any .NET debug adapter that supports method calls in expressions (vsdbg via the C# extension).
 *
 * Protocol:
 *   1. System.Reflection.Assembly.LoadFrom(@"<ext>/dist/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll")
 *   2. IQueryableObjectSource.QueryPlanHelper.GetPlanFile(<expression>)  -> path of a generated HTML file
 */
export const HELPER_TYPE = 'IQueryableObjectSource.QueryPlanHelper';

export class EvaluationError extends Error {
  constructor(message: string, readonly expression: string) {
    super(message);
  }
}

/** C# verbatim string literal. */
export function csharpString(value: string): string {
  return `@"${value.replace(/"/g, '""')}"`;
}

/** Turns vsdbg's display form of a System.String (`"C:\\foo\\bar"`) back into the raw value. */
export function parseCSharpStringResult(result: string): string {
  const trimmed = result.trim();
  if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
    return trimmed
      .slice(1, -1)
      .replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, esc: string) => {
        switch (esc[0]) {
          case 'n': return '\n';
          case 'r': return '\r';
          case 't': return '\t';
          case '0': return '\0';
          case 'u': return String.fromCharCode(parseInt(esc.slice(1), 16));
          default: return esc;
        }
      });
  }
  return trimmed;
}

export interface EvaluateResponse {
  result: string;
  type?: string;
  variablesReference: number;
  presentationHint?: { attributes?: string[] };
}

export async function evaluate(session: vscode.DebugSession, frameId: number, expression: string): Promise<EvaluateResponse> {
  let response: EvaluateResponse;
  try {
    response = await session.customRequest('evaluate', { expression, frameId, context: 'repl' });
  } catch (e) {
    throw new EvaluationError(e instanceof Error ? e.message : String(e), expression);
  }
  // vsdbg reports an exception thrown by func-eval as a normal response flagged "failedEvaluation":
  //   result: "'X.GetPlanFile(context)' threw an exception of type 'System.ArgumentException'"
  //   type:   "string {System.ArgumentException}", variablesReference -> the exception object
  if (response.presentationHint?.attributes?.includes('failedEvaluation')) {
    const message = await readExceptionMessage(session, response.variablesReference);
    throw new EvaluationError(message ? `${response.result}: ${message}` : response.result, expression);
  }
  return response;
}

async function readExceptionMessage(session: vscode.DebugSession, variablesReference: number): Promise<string | undefined> {
  if (!variablesReference) {
    return undefined;
  }
  try {
    const { variables } = await session.customRequest('variables', { variablesReference });
    // vsdbg names children "Message [string]"; other adapters may use plain "Message".
    const message = (variables as { name: string; value: string }[]).find((v) => /^Message(\s|$)/.test(v.name));
    return message ? parseCSharpStringResult(message.value) : undefined;
  } catch {
    return undefined;
  }
}

async function evaluateString(session: vscode.DebugSession, frameId: number, expression: string): Promise<string> {
  const response = await evaluate(session, frameId, expression);
  if (response.type !== 'string') {
    throw new EvaluationError(`Expected a string but got ${response.type}: ${response.result}`, expression);
  }
  return parseCSharpStringResult(response.result);
}

export async function loadHelper(session: vscode.DebugSession, frameId: number, helperPath: string): Promise<EvaluateResponse> {
  return evaluate(session, frameId, `System.Reflection.Assembly.LoadFrom(${csharpString(helperPath)})`);
}

export function getPlanFile(session: vscode.DebugSession, frameId: number, queryExpression: string): Promise<string> {
  return evaluateString(session, frameId, `${HELPER_TYPE}.GetPlanFile(${queryExpression})`);
}

export function getQueryFile(session: vscode.DebugSession, frameId: number, queryExpression: string): Promise<string> {
  return evaluateString(session, frameId, `${HELPER_TYPE}.GetQueryFile(${queryExpression})`);
}
