import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, getQueryString, loadHelper } from './debuggee';
import { withProgressNotification } from './progress';
import { RatingPrompt, reviewUrl } from './rating';
import { PlanView } from './webview';

export const VISUALIZE_COMMAND = 'efcoreVisualizer.visualizeQuery';
export const REFRESH_COMMAND = 'efcoreVisualizer.refreshQueryPlan';
export const COPY_SQL_COMMAND = 'efcoreVisualizer.copyQuerySql';

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
  /** The query's SQL, with parameter values, when the tab was filled: what Copy SQL copies. Undefined if it couldn't be generated. */
  sql?: string;
  /**
   * The SQL the tab's plan was made for. Undefined while the tab shows only the SQL (the plan failed), so that
   * visualizing the variable again retries the plan instead of just bringing the tab to the front.
   */
  planSql?: string;
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
  /** The tab that most recently got the focus (see activeTab). */
  private lastFocusedTab: PlanTab | undefined;
  /** Visualizations still running, by session and expression (see show). */
  private readonly running = new Map<string, Promise<VisualizeResult>>();

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly log: vscode.LogOutputChannel,
    private readonly rating: RatingPrompt,
  ) {}

  register(): vscode.Disposable[] {
    return [
      vscode.commands.registerCommand(VISUALIZE_COMMAND, (arg?: VariableContext) => this.visualize(arg)),
      vscode.commands.registerCommand(REFRESH_COMMAND, () => this.refresh()),
      vscode.commands.registerCommand(COPY_SQL_COMMAND, () => this.copySql()),
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
      const { session, frameId } = await currentFrame(arg?.sessionId, 'Select a stack frame in the debug session of that variable.');
      const typed =
        arg?.variable?.evaluateName ??
        (await vscode.window.showInputBox({ prompt: 'IQueryable expression to visualize', placeHolder: 'query' }));
      const expression = typed && withoutFormatSpecifiers(typed);
      if (!expression) {
        return undefined;
      }
      return this.show(session, frameId, expression, false);
    });
  }

  /** The Refresh button of a plan tab: gets the plan again even if the query hasn't changed (the data may have). */
  async refresh(): Promise<VisualizeResult | undefined> {
    return this.reportErrors(async () => {
      const tab = this.activeTab();
      if (tab.sessionEnded) {
        throw new Error('The debug session of this plan has ended. Visualize the query again in the new session.');
      }
      const { session, frameId } = await currentFrame(tab.sessionId, 'Select a stack frame in the debug session of this plan.');
      return this.show(session, frameId, tab.expression, true);
    });
  }

  /**
   * Shows the plan of `expression`. If it's already being retrieved (a second click while SQL Server, PostgreSQL or
   * Oracle still runs the query), this waits for that run instead of starting another: the query would run twice,
   * and a second tab would open for the same variable.
   */
  private show(session: vscode.DebugSession, frameId: number, expression: string, refresh: boolean): Promise<VisualizeResult> {
    const key = `${session.id}\n${expression}`;
    const running = this.running.get(key);
    if (running) {
      return running;
    }
    const run = this.showNow(session, frameId, expression, key, refresh).finally(() => this.running.delete(key));
    this.running.set(key, run);
    return run;
  }

  private async showNow(session: vscode.DebugSession, frameId: number, expression: string, key: string, refresh: boolean): Promise<VisualizeResult> {
    if (session.type !== 'coreclr') {
      throw new Error(`EFCore Visualizer needs a .NET (coreclr) debug session, not "${session.type}".`);
    }
    await loadHelper(session, frameId, helperAssemblyPath(this.context));

    // If the variable already has a tab and its SQL (parameter values included) is the same, that tab is up to date.
    const existing = this.tabsByVariable.get(key);
    let sql: string | undefined;
    try {
      sql = await getQueryString(session, frameId, expression);
    } catch (e) {
      logError(this.log, `No SQL for ${expression}`, e); // can't compare: get the plan again
    }
    if (existing && !refresh && sql !== undefined && sql === existing.planSql) {
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
      const shown = this.fillTab(key, session.id, expression, sql, sql, planFile, `Query Plan: ${expression}`);
      // Only a shown plan counts towards asking for a rating; a rating problem must never fail the plan.
      this.rating.planShown().catch((e) => logError(this.log, 'Rating prompt failed', e));
      return { kind: 'plan', ...shown };
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
    // No planSql for the SQL-only page: visualizing the variable again must retry the plan, not just focus this tab.
    return { kind: 'query', ...this.fillTab(key, session.id, expression, sql, undefined, queryFile, `Query: ${expression}`), warning };
  }

  /** Shows the page in the variable's tab, opening one if it has none. */
  private fillTab(key: string, sessionId: string, expression: string, sql: string | undefined, planSql: string | undefined, file: string, title: string) {
    let tab = this.tabsByVariable.get(key);
    const isNew = !tab;
    if (!tab) {
      const newTab: PlanTab = { view: new PlanView(title, this.columnForNewTab()), sessionId, expression };
      this.tabsByVariable.set(key, newTab);
      this.openTabs.add(newTab);
      this.lastFocusedTab = newTab; // a new panel opens with the focus
      newTab.view.panel.onDidChangeViewState(({ webviewPanel }) => {
        if (webviewPanel.active) {
          this.lastFocusedTab = newTab;
        }
      });
      newTab.view.panel.onDidDispose(() => {
        this.openTabs.delete(newTab);
        if (this.lastFocusedTab === newTab) {
          this.lastFocusedTab = undefined;
        }
        if (this.tabsByVariable.get(key) === newTab) {
          this.tabsByVariable.delete(key);
        }
      });
      tab = newTab;
    }
    try {
      const html = tab.view.show(file, title);
      tab.sql = sql;
      tab.planSql = planSql;
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

  /** The Copy SQL button of a plan tab. Works after the debug session has ended too. Returns the copied text. */
  async copySql(): Promise<string | undefined> {
    try {
      const tab = this.activeTab();
      if (tab.sql === undefined) {
        throw new Error("The SQL of this query couldn't be generated. The EFCore Visualizer output channel has the error.");
      }
      await vscode.env.clipboard.writeText(tab.sql);
      vscode.window.setStatusBarMessage('$(check) EFCore Visualizer: SQL copied', 3000);
      return tab.sql;
    } catch (e) {
      logError(this.log, 'Copy SQL failed', e);
      void vscode.window.showErrorMessage(`EFCore Visualizer: ${messageOf(e)}`);
      return undefined;
    }
  }

  /**
   * The plan tab that has the focus. When the focus moves from one plan tab to another, VS Code updates the two
   * panels in separate events, so for a moment both can report `active`: the tab that got the focus last wins.
   */
  private activeTab(): PlanTab {
    const tab = this.lastFocusedTab?.view.panel.active ? this.lastFocusedTab : [...this.openTabs].find((t) => t.view.panel.active);
    if (!tab) {
      throw new Error('Select a query plan tab first.');
    }
    return tab;
  }

  /**
   * A new plan opens in the column right of the newest open plan, so plans line up side by side for comparing.
   * The newest plan's own column counts, not the focused group: "beside the active editor" would put the new
   * plan into the same group as an earlier one whenever the code editor had the focus. VS Code has at most 9
   * columns; from there on, new plans join the last one as tabs.
   */
  private columnForNewTab(): vscode.ViewColumn {
    const newest = [...this.openTabs].pop()?.view.panel.viewColumn;
    return newest ? Math.min(newest + 1, vscode.ViewColumn.Nine) : vscode.ViewColumn.Beside;
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

/** The selected stack frame. `otherSession`: the error when it belongs to another session than `sessionId`. */
async function currentFrame(sessionId: string | undefined, otherSession: string): Promise<{ session: vscode.DebugSession; frameId: number }> {
  const item = vscode.debug.activeStackItem;
  if (!item) {
    throw new Error('Pause the debugger and select a stack frame first.');
  }
  if (sessionId && item.session.id !== sessionId) {
    throw new Error(otherSession);
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

/**
 * A watch expression can end in debugger format specifiers, such as "query, nq". They aren't C#, and the expression is
 * passed to a method call, so they're removed. Only known specifiers after the last top-level comma are removed: a
 * C# expression can't end in ", nq" any other way.
 */
const FORMAT_SPECIFIERS = /(\s*,\s*(?:nq|nse|ac|d|h|raw|results|dynamic|hidden))+\s*$/;

export function withoutFormatSpecifiers(expression: string): string {
  return expression.replace(FORMAT_SPECIFIERS, '').trim();
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
  const rating = new RatingPrompt(context.globalState, reviewUrl(context.extension.id));
  context.subscriptions.push(log, ...new Visualizer(context, log, rating).register());
}

export function deactivate(): void {}
