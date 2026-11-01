import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, loadHelper } from '../../debuggee';
import { VISUALIZE_COMMAND, VisualizeResult } from '../../extension';

const extensionRoot = path.resolve(__dirname, '../../..');
const workspace = process.env.EFCORE_VISUALIZER_TEST_WORKSPACE ?? path.join(extensionRoot, 'test-fixtures/SampleApp');
const programCs = path.join(workspace, 'Program.cs');
const programDll = path.join(workspace, 'bin/Debug/net8.0/SampleApp.dll');
const helperDll = path.join(extensionRoot, 'dist/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll');

interface Stopped {
  session: vscode.DebugSession;
  threadId: number;
  reason: string;
}

function waitForStop(): Promise<Stopped> {
  return new Promise((resolve, reject) => {
    const disposables: vscode.Disposable[] = [];
    const done = () => disposables.forEach((d) => d.dispose());
    disposables.push(
      vscode.debug.registerDebugAdapterTrackerFactory('coreclr', {
        createDebugAdapterTracker: (session) => ({
          onDidSendMessage: (m: any) => {
            if (m.type === 'event' && m.event === 'output' && m.body?.category !== 'telemetry') {
              process.stdout.write(`[debuggee:${m.body?.category}] ${m.body?.output}`);
            }
            if (m.type === 'event' && m.event === 'stopped') {
              done();
              resolve({ session, threadId: m.body.threadId, reason: m.body.reason });
            }
          },
          onError: (e) => console.error('[debug adapter error]', e),
        }),
      }),
      vscode.debug.onDidTerminateDebugSession(() => {
        done();
        reject(new Error('Debug session terminated before hitting the breakpoint'));
      }),
    );
  });
}

async function waitFor<T>(probe: () => T | undefined, what: string, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = probe();
    if (value !== undefined) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out waiting for ${what}`);
    }
    await new Promise((r) => setTimeout(r, 100));
  }
}

describe('EFCore Visualizer against a real coreclr (vsdbg) debug session', () => {
  let session: vscode.DebugSession;
  let frameId: number;
  let breakpointLine: number;

  before(async () => {
    assert.ok(fs.existsSync(programDll), `Sample app not built: ${programDll}`);
    assert.ok(fs.existsSync(helperDll), `Helper not built: ${helperDll}`);

    const csharp = vscode.extensions.getExtension('ms-dotnettools.csharp');
    assert.ok(csharp, 'C# extension (ms-dotnettools.csharp) is not installed');
    await csharp.activate();

    const lines = fs.readFileSync(programCs, 'utf-8').split(/\r?\n/);
    breakpointLine = lines.findIndex((l) => l.includes('// BREAKPOINT'));
    assert.ok(breakpointLine >= 0, 'BREAKPOINT marker not found in Program.cs');

    vscode.debug.addBreakpoints([
      new vscode.SourceBreakpoint(new vscode.Location(vscode.Uri.file(programCs), new vscode.Position(breakpointLine, 0))),
    ]);

    const stoppedPromise = waitForStop();
    const started = await vscode.debug.startDebugging(vscode.workspace.workspaceFolders?.[0], {
      type: 'coreclr',
      request: 'launch',
      name: 'EFCore.Visualizer integration test',
      program: programDll,
      cwd: workspace,
      console: 'internalConsole',
      stopAtEntry: false,
      justMyCode: true,
    });
    assert.ok(started, 'startDebugging returned false');

    const stopped = await stoppedPromise;
    assert.strictEqual(stopped.reason, 'breakpoint');
    session = stopped.session;

    const stack = await session.customRequest('stackTrace', { threadId: stopped.threadId, startFrame: 0, levels: 1 });
    const frame = stack.stackFrames[0];
    assert.strictEqual(path.basename(frame.source?.path ?? ''), 'Program.cs');
    assert.strictEqual(frame.line, breakpointLine + 1);
    frameId = frame.id;
  });

  after(async () => {
    await vscode.debug.stopDebugging();
    vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
  });

  it('loads the helper with Assembly.LoadFrom and calls GetPlanFile(query)', async () => {
    const loaded = await loadHelper(session, frameId, helperDll);
    console.log('Assembly.LoadFrom =>', loaded.type, loaded.result);
    assert.match(loaded.result, /EFCore\.Visualizer\.DebuggeeHelper/);

    const queryFile = await getQueryFile(session, frameId, 'query');
    console.log('GetQueryFile(query) =>', queryFile);
    assert.ok(fs.existsSync(queryFile), `Query file does not exist: ${queryFile}`);
    assert.match(fs.readFileSync(queryFile, 'utf-8'), /FROM &quot;Blogs&quot;|FROM "Blogs"/);

    const planFile = await getPlanFile(session, frameId, 'query');
    console.log('GetPlanFile(query) =>', planFile);
    assert.ok(fs.existsSync(planFile), `Plan file does not exist: ${planFile}`);
    const plan = fs.readFileSync(planFile, 'utf-8');
    assert.match(plan, /Query Plan/);
    assert.match(plan, /SEARCH \w+ USING INDEX IX_Blogs_Rating/);

    // Exceptions thrown inside the debuggee must surface as EvaluationError, not as a bogus path.
    await assert.rejects(getPlanFile(session, frameId, 'context'), (e: unknown) => {
      console.log('GetPlanFile(context) =>', (e as Error).message);
      return e instanceof EvaluationError && /System\.ArgumentException.*Expected an IQueryable/.test(e.message);
    });

    fs.rmSync(queryFile, { force: true });
    fs.rmSync(planFile, { force: true });
  });

  it('Visualize Query Plan command (Variables view context menu) opens the plan in a webview', async () => {
    await waitFor(() => vscode.debug.activeStackItem, 'the stopped frame to become the active stack item');
    const sqliteDir = path.join(extensionRoot, 'dist/Resources/SQLite');
    const filesBefore = fs.readdirSync(sqliteDir).sort();

    // Same argument shape VS Code passes from the debug/variables/context menu.
    const result = await vscode.commands.executeCommand<VisualizeResult | undefined>(VISUALIZE_COMMAND, {
      sessionId: session.id,
      container: { name: 'Locals', variablesReference: 0, expensive: false },
      variable: { name: 'query', evaluateName: 'query', value: '', variablesReference: 0 },
    });

    assert.ok(result, 'command returned nothing (an error message was shown)');
    assert.strictEqual(result.kind, 'plan');
    assert.strictEqual(result.panel.title, 'Query Plan: query');
    assert.match(result.html, /<base href="[^"]*vscode-(cdn|resource)[^"]*\/Resources\/SQLite\/">/);
    assert.match(result.html, /SEARCH \w+ USING INDEX IX_Blogs_Rating/);
    assert.deepStrictEqual(
      fs.readdirSync(sqliteDir).sort(),
      filesBefore,
      'generated plan file should be deleted once loaded into the webview',
    );
    result.panel.dispose();
  });
});
