import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, getQueryFingerprint, loadHelper } from './debuggee';
import { withProgressNotification } from './progress';
import { PlanView } from './webview';

export const VISUALIZE_COMMAND = 'efcoreVisualizer.visualizeQuery';
export const REFRESH_COMMAND = 'efcoreVisualizer.refreshQueryPlan';

/** Argument VS Code passes to commands contributed to `debug/variables/context` and `debug/watch/context`. */
interface VariableContext {
  sessionId?: string;
  variable?: { name: string; evaluateName?: string };
}

export type VisualizeResult =
  | { kind: 'plan'; html: string; panel: vscode.WebviewPanel }
  /** No plan: the SQL is shown instead, and `warning` says why. */
  | { kind: 'query'; html: string; panel: vscode.WebviewPanel; warning: string }
  /** The query hasn't changed since its tab was filled, so that tab was only brought to the front. */
  | { kind: 'unchanged'; panel: vscode.WebviewPanel }
  /** Nothing could be shown; `message` is the error that was reported. */
  | { kind: 'error'; message: string };

/** The tab that shows one variable: an expression in one debug session. */
interface PlanTab {
  readonly view: PlanView;
  readonly sessionId: string;
  readonly expression: string;
  /** SHA-256 of the query's SQL (with parameter values) when the tab was filled; undefined if it couldn't be generated. */
  fingerprint?: string;
  /** The debug session has ended: the tab stays open, but it can't be refreshed. */
  sessionEnded?: boolean;
}

export function helperAssemblyPath(context: vscode.ExtensionContext): string {
  return path.join(context.extensionPath, 'dist', 'netcoreapp', 'EFCore.Visualizer.Core.dll');
}

class Visualizer {
  /** Open tabs of running sessions, by session and expression. */
  private readonly tabsByVariable = new Map<string, PlanTab>();
  /** All open tabs, including those of ended sessions. */
  private readonly openTabs = new Set<PlanTab>();

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly log: vscode.LogOutputChannel,
  ) {}

  register(): vscode.Disposable[] {
    return [
      vscode.commands.registerCommand(VISUALIZE_COMMAND, (arg?: VariableContext) => this.visualize(arg)),
      vscode.commands.registerCommand(REFRESH_COMMAND, () => this.refresh()),
      // The next session's "query" is a different variable: it gets a new tab.
      vscode.debug.onDidTerminateDebugSession((session) => {
        for (const [key, tab] of this.tabsByVariable) {
          if (tab.sessionId === session.id) {
            tab.sessionEnded = true;
            this.tabsByVariable.delete(key);
          }
        }
      }),
    ];
  }

  /** Visualize Query Plan, from the Variables or Watch context menu (`arg`) or the Command Palette. */
  async visualize(arg?: VariableContext): Promise<VisualizeResult | undefined> {
    return this.reportErrors(async () => {
      const { session, frameId } = await currentFrame(arg?.sessionId, 'Pause the debugger and select a stack frame first.');
      const expression =
        arg?.variable?.evaluateName ??
        (await vscode.window.showInputBox({ prompt: 'IQueryable expression to visualize', placeHolder: 'query' }));
      if (!expression) {
        return undefined;
      }
      return this.show(session, frameId, expression, false);
    });
  }

  /** The Refresh button of a plan tab: gets the plan again even if the query hasn't changed (the data may have). */
  async refresh(): Promise<VisualizeResult | undefined> {
    return this.reportErrors(async () => {
      const tab = [...this.openTabs].find((t) => t.view.panel.active);
      if (!tab) {
        throw new Error('Select a query plan tab first.');
      }
      if (tab.sessionEnded) {
        throw new Error('The debug session of this plan has ended. Visualize the query again in the new session.');
      }
      const { session, frameId } = await currentFrame(tab.sessionId, 'Select a stack frame in the debug session of this plan.');
      return this.show(session, frameId, tab.expression, true);
    });
  }

  private async show(session: vscode.DebugSession, frameId: number, expression: string, refresh: boolean): Promise<VisualizeResult> {
    if (session.type !== 'coreclr') {
      throw new Error(`EFCore Visualizer needs a .NET (coreclr) debug session, not "${session.type}".`);
    }
    await loadHelper(session, frameId, helperAssemblyPath(this.context));

    // If the variable already has a tab and its SQL (parameter values included) is the same, that tab is up to date.
    const key = `${session.id}\n${expression}`;
    const existing = this.tabsByVariable.get(key);
    let fingerprint: string | undefined;
    try {
      fingerprint = await getQueryFingerprint(session, frameId, expression);
    } catch (e) {
      logError(this.log, `No SQL fingerprint for ${expression}`, e); // can't compare: get the plan again
    }
    if (existing && !refresh && fingerprint !== undefined && fingerprint === existing.fingerprint) {
      existing.view.panel.reveal();
      return { kind: 'unchanged', panel: existing.view.panel };
    }

    // Only the plan retrieval is caught here: an error while showing the page is not a plan error.
    let planFile: string | undefined;
    let planError: unknown;
    try {
      planFile = await withProgressNotification('Getting the query plan', getPlanFile(session, frameId, expression));
    } catch (e) {
      planError = e;
      logError(this.log, `No plan for ${expression}`, e);
    }
    if (planFile) {
      return { kind: 'plan', ...this.fillTab(key, session.id, expression, fingerprint, planFile, `Query Plan: ${expression}`) };
    }

    // Same behaviour as the VS visualizer: report why there is no plan, then show the SQL.
    let queryFile: string;
    try {
      queryFile = await getQueryFile(session, frameId, expression);
    } catch (queryError) {
      logError(this.log, `No SQL for ${expression}`, queryError);
      throw new Error(`Couldn't get the query plan: ${sentence(messageOf(planError))} Couldn't get the SQL either: ${sentence(messageOf(queryError))}`);
    }
    const warning = `EFCore Visualizer: ${messageOf(planError)}`;
    void vscode.window.showWarningMessage(warning);
    // No fingerprint for the SQL-only page: visualizing the variable again must retry the plan, not just focus this tab.
    return { kind: 'query', ...this.fillTab(key, session.id, expression, undefined, queryFile, `Query: ${expression}`), warning };
  }

  /** Shows the page in the variable's tab, opening one if it has none. */
  private fillTab(key: string, sessionId: string, expression: string, fingerprint: string | undefined, file: string, title: string) {
    let tab = this.tabsByVariable.get(key);
    const isNew = !tab;
    if (!tab) {
      const newTab: PlanTab = { view: new PlanView(title), sessionId, expression };
      this.tabsByVariable.set(key, newTab);
      this.openTabs.add(newTab);
      newTab.view.panel.onDidDispose(() => {
        this.openTabs.delete(newTab);
        if (this.tabsByVariable.get(key) === newTab) {
          this.tabsByVariable.delete(key);
        }
      });
      tab = newTab;
    }
    try {
      const html = tab.view.show(file, title);
      tab.fingerprint = fingerprint;
      if (!isNew) {
        tab.view.panel.reveal();
      }
      return { html, panel: tab.view.panel };
    } catch (e) {
      if (isNew) {
        tab.view.panel.dispose(); // don't leave an empty tab behind
      }
      throw e;
    }
  }

  private async reportErrors(work: () => Promise<VisualizeResult | undefined>): Promise<VisualizeResult | undefined> {
    try {
      return await work();
    } catch (e) {
      const message = `EFCore Visualizer: ${messageOf(e)}`;
      logError(this.log, 'Visualize Query Plan failed', e);
      void vscode.window.showErrorMessage(message);
      return { kind: 'error', message };
    }
  }
}

async function currentFrame(sessionId: string | undefined, notSelected: string): Promise<{ session: vscode.DebugSession; frameId: number }> {
  const item = vscode.debug.activeStackItem;
  if (!item || (sessionId && item.session.id !== sessionId)) {
    throw new Error(notSelected);
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
  context.subscriptions.push(log, ...new Visualizer(context, log).register());
}

export function deactivate(): void {}
