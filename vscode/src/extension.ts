import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, loadHelper } from './debuggee';
import { withProgressNotification } from './progress';
import { showHtmlFile } from './webview';

export const VISUALIZE_COMMAND = 'efcoreVisualizer.visualizeQuery';

/** Argument VS Code passes to commands contributed to `debug/variables/context` and `debug/watch/context`. */
interface VariableContext {
  sessionId?: string;
  variable?: { name: string; evaluateName?: string };
}

export type VisualizeResult =
  | { kind: 'plan'; html: string; panel: vscode.WebviewPanel }
  /** No plan: the SQL is shown instead, and `warning` says why. */
  | { kind: 'query'; html: string; panel: vscode.WebviewPanel; warning: string }
  /** Nothing could be shown; `message` is the error that was reported. */
  | { kind: 'error'; message: string };

export function helperAssemblyPath(context: vscode.ExtensionContext): string {
  return path.join(context.extensionPath, 'dist', 'netcoreapp', 'EFCore.Visualizer.DebuggeeHelper.dll');
}

async function currentFrame(sessionId?: string): Promise<{ session: vscode.DebugSession; frameId: number }> {
  const item = vscode.debug.activeStackItem;
  if (!item || (sessionId && item.session.id !== sessionId)) {
    throw new Error('Pause the debugger and select a stack frame first.');
  }
  if (item instanceof vscode.DebugStackFrame) {
    return { session: item.session, frameId: item.frameId };
  }
  // A thread is selected: use its top frame.
  const stack = await item.session.customRequest('stackTrace', { threadId: item.threadId, startFrame: 0, levels: 1 });
  const frame = stack.stackFrames?.[0];
  if (!frame) {
    throw new Error('The selected thread has no stack frames.');
  }
  return { session: item.session, frameId: frame.id };
}

async function visualize(context: vscode.ExtensionContext, log: vscode.LogOutputChannel, arg?: VariableContext): Promise<VisualizeResult | undefined> {
  try {
    const { session, frameId } = await currentFrame(arg?.sessionId);
    if (session.type !== 'coreclr') {
      throw new Error(`EFCore Visualizer needs a .NET (coreclr) debug session, not "${session.type}".`);
    }

    const expression =
      arg?.variable?.evaluateName ??
      (await vscode.window.showInputBox({ prompt: 'IQueryable expression to visualize', placeHolder: 'query' }));
    if (!expression) {
      return undefined;
    }

    await loadHelper(session, frameId, helperAssemblyPath(context));

    // Only the plan retrieval is caught here: an error while showing the page is not a plan error.
    let planFile: string | undefined;
    let planError: unknown;
    try {
      planFile = await withProgressNotification('Getting the query plan', getPlanFile(session, frameId, expression));
    } catch (e) {
      planError = e;
      logError(log, `No plan for ${expression}`, e);
    }
    if (planFile) {
      return { kind: 'plan', ...showHtmlFile(planFile, `Query Plan: ${expression}`) };
    }

    // Same behaviour as the VS visualizer: report why there is no plan, then show the SQL.
    let queryFile: string;
    try {
      queryFile = await getQueryFile(session, frameId, expression);
    } catch (queryError) {
      logError(log, `No SQL for ${expression}`, queryError);
      throw new Error(`Couldn't get the query plan: ${sentence(messageOf(planError))} Couldn't get the SQL either: ${sentence(messageOf(queryError))}`);
    }
    const warning = `EFCore Visualizer: ${messageOf(planError)}`;
    void vscode.window.showWarningMessage(warning);
    return { kind: 'query', ...showHtmlFile(queryFile, `Query: ${expression}`), warning };
  } catch (e) {
    const message = `EFCore Visualizer: ${messageOf(e)}`;
    logError(log, 'Visualize Query Plan failed', e);
    void vscode.window.showErrorMessage(message);
    return { kind: 'error', message };
  }
}

/** Ends `text` with a period unless it already ends a sentence, so two messages can be joined. */
function sentence(text: string): string {
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** The full text goes to the output channel; the user sees only the short message. */
function logError(log: vscode.LogOutputChannel, what: string, e: unknown): void {
  log.error(`${what}: ${e instanceof EvaluationError ? `${e.detail} (expression: ${e.expression})` : e instanceof Error ? e.stack ?? e.message : String(e)}`);
}

export function activate(context: vscode.ExtensionContext): void {
  const log = vscode.window.createOutputChannel('EFCore Visualizer', { log: true });
  context.subscriptions.push(
    log,
    vscode.commands.registerCommand(VISUALIZE_COMMAND, (arg?: VariableContext) => visualize(context, log, arg)),
  );
}

export function deactivate(): void {}
