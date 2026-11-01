import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, loadHelper } from './debuggee';
import { showHtmlFile } from './webview';

export const VISUALIZE_COMMAND = 'efcoreVisualizer.visualizeQuery';

/** Argument VS Code passes to commands contributed to `debug/variables/context` and `debug/watch/context`. */
interface VariableContext {
  sessionId?: string;
  variable?: { name: string; evaluateName?: string };
}

export interface VisualizeResult {
  kind: 'plan' | 'query';
  html: string;
  panel: vscode.WebviewPanel;
}

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

async function visualize(context: vscode.ExtensionContext, arg?: VariableContext): Promise<VisualizeResult | undefined> {
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

    try {
      const planFile = await getPlanFile(session, frameId, expression);
      return { kind: 'plan', ...showHtmlFile(planFile, `Query Plan: ${expression}`) };
    } catch (planError) {
      // Same behaviour as the VS visualizer: report why there is no plan, then show the SQL.
      const queryFile = await getQueryFile(session, frameId, expression);
      void vscode.window.showWarningMessage(`EFCore Visualizer: ${messageOf(planError)}`);
      return { kind: 'query', ...showHtmlFile(queryFile, `Query: ${expression}`) };
    }
  } catch (e) {
    void vscode.window.showErrorMessage(`EFCore Visualizer: ${messageOf(e)}`);
    return undefined;
  }
}

function messageOf(e: unknown): string {
  return e instanceof EvaluationError || e instanceof Error ? e.message : String(e);
}

export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.commands.registerCommand(VISUALIZE_COMMAND, (arg?: VariableContext) => visualize(context, arg)),
  );
}

export function deactivate(): void {}
