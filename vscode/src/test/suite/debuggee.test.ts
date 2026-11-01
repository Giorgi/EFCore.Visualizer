import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { EvaluationError, getPlanFile, getQueryFile, loadHelper } from '../../debuggee';

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

describe('Debuggee helper round-trip (coreclr / vsdbg)', () => {
  before(async () => {
    assert.ok(fs.existsSync(programDll), `Sample app not built: ${programDll}`);
    assert.ok(fs.existsSync(helperDll), `Helper not built: ${helperDll}`);

    const csharp = vscode.extensions.getExtension('ms-dotnettools.csharp');
    assert.ok(csharp, 'C# extension (ms-dotnettools.csharp) is not installed');
    await csharp.activate();
  });

  after(async () => {
    await vscode.debug.stopDebugging();
    vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
  });

  it('stops at a breakpoint, loads the helper with Assembly.LoadFrom and calls GetPlanFile(query)', async () => {
    const lines = fs.readFileSync(programCs, 'utf-8').split(/\r?\n/);
    const line = lines.findIndex((l) => l.includes('// BREAKPOINT'));
    assert.ok(line >= 0, 'BREAKPOINT marker not found in Program.cs');

    vscode.debug.addBreakpoints([
      new vscode.SourceBreakpoint(new vscode.Location(vscode.Uri.file(programCs), new vscode.Position(line, 0))),
    ]);

    const stopped = waitForStop();
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

    const { session, threadId, reason } = await stopped;
    assert.strictEqual(reason, 'breakpoint');

    const stack = await session.customRequest('stackTrace', { threadId, startFrame: 0, levels: 1 });
    const frame = stack.stackFrames[0];
    assert.strictEqual(path.basename(frame.source?.path ?? ''), 'Program.cs');
    assert.strictEqual(frame.line, line + 1);

    const loaded = await loadHelper(session, frame.id, helperDll);
    console.log('Assembly.LoadFrom =>', loaded.type, loaded.result);
    assert.match(loaded.result, /EFCore\.Visualizer\.DebuggeeHelper/);

    const queryFile = await getQueryFile(session, frame.id, 'query');
    console.log('GetQueryFile(query) =>', queryFile);
    assert.ok(fs.existsSync(queryFile), `Query file does not exist: ${queryFile}`);
    assert.match(fs.readFileSync(queryFile, 'utf-8'), /FROM &quot;Blogs&quot;|FROM "Blogs"/);

    const planFile = await getPlanFile(session, frame.id, 'query');
    console.log('GetPlanFile(query) =>', planFile);
    assert.ok(fs.existsSync(planFile), `Plan file does not exist: ${planFile}`);
    const plan = fs.readFileSync(planFile, 'utf-8');
    assert.match(plan, /Query Plan/);
    assert.match(plan, /SEARCH \w+ USING INDEX IX_Blogs_Rating/);

    // Exceptions thrown inside the debuggee must surface as EvaluationError, not as a bogus path.
    await assert.rejects(getPlanFile(session, frame.id, 'context'), (e: unknown) => {
      console.log('GetPlanFile(context) =>', (e as Error).message);
      return e instanceof EvaluationError && /System\.ArgumentException.*Expected an IQueryable/.test(e.message);
    });

    fs.rmSync(queryFile, { force: true });
    fs.rmSync(planFile, { force: true });
  });
});
