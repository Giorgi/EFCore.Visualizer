import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { csharpString, evaluate, EvaluationError, getPlanFile, getQueryFile, HELPER_TYPE, loadHelper, parseCSharpStringResult } from '../../debuggee';
import { assetFiles, contentSecurityPolicy, PlanView } from '../../webview';
import { REFRESH_COMMAND, VISUALIZE_COMMAND, VisualizeResult } from '../../extension';

const extensionRoot = path.resolve(__dirname, '../../..');
const workspace = process.env.EFCORE_VISUALIZER_TEST_WORKSPACE ?? path.join(extensionRoot, 'test-fixtures/SampleApp');
const programCs = path.join(workspace, 'Program.cs');
const programDll = path.join(workspace, 'bin/Debug/net8.0/SampleApp.dll');
const helperDll = path.join(extensionRoot, 'dist/netcoreapp/EFCore.Visualizer.Core.dll');
const resourcesDir = path.join(extensionRoot, 'dist/Resources');

/** Files the helper generated in the temp folder. */
function generatedPages(): string[] {
  return fs.readdirSync(os.tmpdir()).filter((f) => /^EFCore\.Visualizer-.*\.html$/.test(f)).sort();
}

/** Lists every file under dist/Resources, to check that nothing is written there. */
function resourceFiles(): string[] {
  return (fs.readdirSync(resourcesDir, { recursive: true }) as string[]).sort();
}

function samePath(a: string, b: string): boolean {
  const normalize = (p: string) => path.resolve(p).replace(/[\\/]+$/, '');
  return process.platform === 'win32' ? normalize(a).toLowerCase() === normalize(b).toLowerCase() : normalize(a) === normalize(b);
}

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

/** Runs the command with the argument VS Code passes from the Variables view's context menu. */
function visualizeVariable(name: string): Thenable<VisualizeResult | undefined> {
  return vscode.commands.executeCommand<VisualizeResult | undefined>(VISUALIZE_COMMAND, {
    sessionId: vscode.debug.activeDebugSession?.id,
    variable: { name, evaluateName: name },
  });
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
    // activate() returns before the C# extension has registered the `coreclr` debug adapter: on a fresh
    // install it first finishes setting up the debugger in the background. Only initializationFinished()
    // waits for that (and for the language server), so startDebugging right after activate() can fail with
    // "Couldn't find a debug adapter descriptor for debug type 'coreclr'".
    const api = await csharp.activate();
    await api?.initializationFinished?.();

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
    const resourcesBefore = resourceFiles();
    const loaded = await loadHelper(session, frameId, helperDll);
    console.log('Assembly.LoadFrom =>', loaded.type, loaded.result);
    assert.match(loaded.result, /EFCore\.Visualizer\.Core/);

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

    // Pages go to the temp folder and link their scripts and styles in dist/Resources by absolute file: URLs.
    for (const [file, html, assets] of [
      [queryFile, fs.readFileSync(queryFile, 'utf-8'), ['Common/highlight.css', 'Common/highlight.dark.min.css', 'Common/highlight.min.js', 'Common/highlight.sql.js']],
      [planFile, plan, ['Common/treeflex.css']],
    ] as const) {
      assert.match(path.basename(file), /^EFCore\.Visualizer-.*\.html$/);
      assert.ok(samePath(path.dirname(file), os.tmpdir()), `${file} is not in the temp folder ${os.tmpdir()}`);
      assert.doesNotMatch(html, /<base\b/i, "a <base> would break the page's own #links");
      const linked = assetFiles(html);
      assert.strictEqual(linked.length, assets.length, `assets linked from ${file}: ${linked.join(', ')}`);
      assets.forEach((asset, i) => {
        assert.ok(samePath(linked[i], path.join(resourcesDir, asset)), `${linked[i]} should be ${asset}`);
        assert.ok(fs.existsSync(linked[i]), `${linked[i]} does not exist`);
      });
    }
    assert.deepStrictEqual(resourceFiles(), resourcesBefore, 'nothing should be written to dist/Resources');

    // Exceptions thrown inside the debuggee must surface as EvaluationError, not as a bogus path.
    await assert.rejects(getPlanFile(session, frameId, 'context'), (e: unknown) => {
      console.log('GetPlanFile(context) =>', (e as Error).message);
      // The message is the exception's own; the debugger's full text is kept in detail.
      return (
        e instanceof EvaluationError &&
        e.message === "Expected an IQueryable but got BloggingContext (Parameter 'query')" &&
        /threw an exception of type 'System\.ArgumentException'/.test(e.detail)
      );
    });

    fs.rmSync(queryFile, { force: true });
    fs.rmSync(planFile, { force: true });
  });

  it('Visualize Query Plan command (Variables view context menu) opens the plan in a webview', async () => {
    await waitFor(() => vscode.debug.activeStackItem, 'the stopped frame to become the active stack item');
    const pagesBefore = generatedPages();
    const resourcesBefore = resourceFiles();

    // Same argument shape VS Code passes from the debug/variables/context menu.
    const result = await vscode.commands.executeCommand<VisualizeResult | undefined>(VISUALIZE_COMMAND, {
      sessionId: session.id,
      container: { name: 'Locals', variablesReference: 0, expensive: false },
      variable: { name: 'query', evaluateName: 'query', value: '', variablesReference: 0 },
    });

    assert.ok(result?.kind === 'plan', `expected a plan, got ${JSON.stringify(result)}`);
    assert.strictEqual(result.panel.title, 'Query Plan: query');
    assert.match(result.html, /<link rel="stylesheet" href="[^"]*vscode-(cdn|resource)[^"]*\/Resources\/Common\/treeflex\.css">/);
    assert.match(result.html, /SEARCH \w+ USING INDEX IX_Blogs_Rating/);
    assert.doesNotMatch(result.html, /file:/, 'every file: link must be replaced with a webview URI');
    // The CSP must come before the first script or stylesheet. It allows only the webview's own resources.
    const policy = contentSecurityPolicy(result.panel.webview.cspSource);
    console.log('CSP =>', policy);
    assert.match(policy, /^default-src 'none'; /);
    assert.doesNotMatch(policy, /connect-src/);
    const csp = result.html.indexOf(`<meta http-equiv="Content-Security-Policy" content="${policy}">`);
    assert.ok(csp >= 0 && csp < result.html.search(/<(script|link)\b/i), 'CSP <meta> missing or after the first asset');
    assert.deepStrictEqual(generatedPages(), pagesBefore, 'generated plan file should be deleted once loaded into the webview');
    assert.deepStrictEqual(resourceFiles(), resourcesBefore, 'nothing should be written to dist/Resources');
    result.panel.dispose();
  });

  it('falls back to the SQL with a short warning, and reports both errors when that fails too', async () => {
    // The plan fails (no such table), the SQL works: the SQL page opens, and the warning is the exception's message.
    const fallback = await visualizeVariable('broken');
    assert.ok(fallback?.kind === 'query', `expected the SQL page, got ${JSON.stringify(fallback)}`);
    console.log('warning =>', fallback.warning);
    assert.match(fallback.warning, /^EFCore Visualizer: Failed to extract execution plan\. .*no such table: MissingTable/);
    assert.doesNotMatch(fallback.warning, /threw an exception/, "the debugger's own wording belongs in the log, not the warning");
    assert.strictEqual(fallback.panel.title, 'Query: broken');
    assert.match(fallback.html, /MissingTable/);

    // Visualizing it again retries the plan (it may have failed for a passing reason) instead of only focusing the tab.
    const retried = await visualizeVariable('broken');
    assert.ok(retried?.kind === 'query', `expected the plan to be retried, got ${JSON.stringify(retried)}`);
    assert.strictEqual(retried.panel, fallback.panel, 'the same tab should be refilled');
    fallback.panel.dispose();

    // Neither works: one error that names both causes.
    const failed = await visualizeVariable('context');
    assert.ok(failed?.kind === 'error', `expected an error, got ${JSON.stringify(failed)}`);
    console.log('error =>', failed.message);
    assert.strictEqual(
      failed.message,
      "EFCore Visualizer: Couldn't get the query plan: Expected an IQueryable but got BloggingContext (Parameter 'query'). " +
        "Couldn't get the SQL either: Expected an IQueryable but got BloggingContext (Parameter 'query').",
    );
  });

  it('escapes the plan and query for the JavaScript strings of the templates', async () => {
    // The query and plan contain data (parameter values, literals). Each of these must not end the string or the script.
    const dangerous = '</script><script>alert(1)</script> \' " ` ${alert(2)} & \\ é';
    // SQLite builds HTML itself and overrides Encode; every other provider fills JavaScript strings.
    for (const provider of ['SqlServerDatabaseProvider', 'PostgresDatabaseProvider', 'OracleDatabaseProvider']) {
      const encoded = parseCSharpStringResult(
        (await evaluate(session, frameId, `new IQueryableObjectSource.${provider}(null).Encode(${csharpString(dangerous)})`)).result,
      );
      console.log(`${provider}.Encode =>`, encoded);
      assert.doesNotMatch(encoded, /[<>'"`&]/, `${provider} left a character that can end a string or the script`);
      assert.strictEqual(JSON.parse(`"${encoded}"`), dangerous, `${provider} must keep the value intact`);
    }
  });

  it('leaves placeholder text inside the plan alone', async () => {
    // The plan uses the index "IX_{query}". Filling the placeholders one after the other would put the query into it.
    const planFile = await getPlanFile(session, frameId, 'postsByTitle');
    const plan = fs.readFileSync(planFile, 'utf-8');
    fs.rmSync(planFile, { force: true });
    assert.match(plan, /SEARCH \w+ USING (COVERING )?INDEX IX_\{query\}/);
    assert.doesNotMatch(plan, /SELECT/, 'the query must not be put into the plan');
  });

  it('templates put {plan} and {query} only where they are escaped', () => {
    // Inside <script>, a placeholder must be a whole "..." or '...' string: Encode doesn't make backtick strings safe,
    // where ${...} still runs. Outside <script>, {plan} is allowed in any template (a provider that puts its plan into
    // HTML builds and encodes that HTML itself), but {query} only in Common, the one page that HTML-encodes the query.
    const allowedOutsideScript = (name: string, placeholder: string) => placeholder === '{plan}' || name === 'Common';
    const templates = fs.readdirSync(resourcesDir).filter((d) => fs.existsSync(path.join(resourcesDir, d, 'template.html')));
    assert.ok(templates.length >= 5, `templates found: ${templates.join(', ')}`);
    for (const name of templates) {
      const html = fs.readFileSync(path.join(resourcesDir, name, 'template.html'), 'utf-8');
      const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => [m.index! + m[0].indexOf(m[1]), m[1].length]);
      for (const m of html.matchAll(/\{(plan|query)\}/g)) {
        const inScript = scripts.some(([start, length]) => m.index! >= start && m.index! < start + length);
        if (inScript) {
          const quote = html[m.index! - 1];
          assert.ok((quote === '"' || quote === "'") && html[m.index! + m[0].length] === quote, `${name}: ${m[0]} is not a whole quoted string`);
        } else {
          assert.ok(allowedOutsideScript(name, m[0]), `${name}: ${m[0]} outside <script> is not HTML-encoded`);
        }
      }
    }
  });

  it('webview loads the page assets from a folder with special characters in its path', async () => {
    // Like an extension under C:\Users\O'Brien: the helper's file: URLs must survive the round trip into the webview.
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "EFCore.Visualizer test O'Brien #1 & é "));
    const page = path.join(os.tmpdir(), `EFCore.Visualizer-test-${path.basename(dir).replace(/\W/g, '')}.html`);
    let view: PlanView | undefined;
    try {
      fs.mkdirSync(path.join(dir, 'sub'));
      fs.writeFileSync(path.join(dir, 'template.html'), '<html><head><script src="a.js"></script><link rel="stylesheet" href="sub\\b.css"></head><body></body></html>');
      const html = parseCSharpStringResult((await evaluate(session, frameId, `${HELPER_TYPE}.ReadTemplate(${csharpString(dir)})`)).result);
      console.log('ReadTemplate =>', html);
      const expected = [path.join(dir, 'a.js'), path.join(dir, 'sub', 'b.css')];
      const linked = assetFiles(html);
      assert.strictEqual(linked.length, expected.length, `assets: ${linked.join(', ')}`);
      expected.forEach((file, i) => assert.ok(samePath(linked[i], file), `${linked[i]} should be ${file}`));

      fs.writeFileSync(page, html);
      view = new PlanView('special characters');
      const webviewHtml = view.show(page, 'special characters');
      for (const file of expected) {
        assert.ok(webviewHtml.includes(view.panel.webview.asWebviewUri(vscode.Uri.file(file)).toString(true)), `no webview URI for ${file}`);
      }
      const roots = (view.panel.webview.options.localResourceRoots ?? []).map((u) => u.fsPath);
      for (const folder of [dir, path.join(dir, 'sub')]) {
        assert.ok(roots.some((r) => samePath(r, folder)), `${folder} not in localResourceRoots: ${roots.join(', ')}`);
      }
    } finally {
      view?.panel.dispose();
      fs.rmSync(page, { force: true });
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('plan page follows the VS Code theme, not the OS', async () => {
    // In a webview, prefers-color-scheme follows the OS (with the default window.systemColorTheme), so the
    // extension resolves the templates' "(prefers-color-scheme: dark)" itself and re-renders on theme changes.
    const workbench = () => vscode.workspace.getConfiguration('workbench');
    const setTheme = async (theme: string, kind: vscode.ColorThemeKind) => {
      await workbench().update('colorTheme', theme, vscode.ConfigurationTarget.Global);
      await waitFor(() => (vscode.window.activeColorTheme.kind === kind ? true : undefined), `${theme} to become active`);
    };
    try {
      await setTheme('Default Dark Modern', vscode.ColorThemeKind.Dark);
      const result = await visualizeVariable('query');
      assert.ok(result?.kind === 'plan', `expected a plan, got ${JSON.stringify(result)}`);
      assert.doesNotMatch(result.html, /prefers-color-scheme:\s*(dark|light)\)/, 'the page must not depend on the OS color scheme');
      assert.match(result.html, /@media all \{/, 'SQLite template should be in dark mode');

      await setTheme('Default Light Modern', vscode.ColorThemeKind.Light);
      await waitFor(() => (/@media not all \{/.test(result.panel.webview.html) ? true : undefined), 'the open plan to switch to light mode');
      assert.match(result.panel.webview.html, /SEARCH \w+ USING INDEX IX_Blogs_Rating/);
      result.panel.dispose();
    } finally {
      await workbench().update('colorTheme', undefined, vscode.ConfigurationTarget.Global);
    }
  });

  it('shows Visualize Query Plan only on EF Core queries and DbSets', async () => {
    // The variableType clause of the Variables/Watch menu entries in package.json.
    const pkg = JSON.parse(fs.readFileSync(path.join(extensionRoot, 'package.json'), 'utf-8'));
    const whens: string[] = ['debug/variables/context', 'debug/watch/context'].map(
      (menu) => pkg.contributes.menus[menu].find((m: { command: string }) => m.command === VISUALIZE_COMMAND).when,
    );
    assert.strictEqual(whens[0], whens[1], 'Variables and Watch menus should use the same condition');
    const clause = /variableType =~ \/(.+?)\/(\s|$)/.exec(whens[0]);
    assert.ok(clause, `no variableType clause in: ${whens[0]}`);
    const typeFilter = new RegExp(clause[1]);

    // Same type strings VS Code puts into the variableType context key.
    const { scopes } = await session.customRequest('scopes', { frameId });
    const { variables } = await session.customRequest('variables', { variablesReference: scopes[0].variablesReference });
    // vsdbg appends the declared type to the name: "query [IOrderedQueryable]".
    const bareName = (name: string) => name.replace(/ \[.*\]$/, '');
    const locals = new Map<string, { type: string; variablesReference: number }>(
      variables.map((v: { name: string; type: string; variablesReference: number }) => [bareName(v.name), v]),
    );
    const contextVar = locals.get('context');
    assert.ok(contextVar, 'no context local');
    const { variables: members } = await session.customRequest('variables', { variablesReference: contextVar.variablesReference });
    const types: Record<string, string> = Object.fromEntries([...locals].map(([name, v]) => [name, v.type]));
    types['context.Blogs'] = members.find((v: { name: string }) => bareName(v.name) === 'Blogs')?.type;
    for (const [name, type] of Object.entries(types)) {
      console.log(`  ${typeFilter.test(type ?? '') ? 'MENU' : '    '}  ${name}: ${type}`);
    }

    for (const name of ['query', 'blogs', 'included', 'enumerable', 'context.Blogs']) {
      assert.match(types[name] ?? '', typeFilter, `${name} should get the menu item`);
      // ...and the menu item must work for it.
      const planFile = await getPlanFile(session, frameId, name);
      assert.match(fs.readFileSync(planFile, 'utf-8'), /Query Plan/, `no plan for ${name}`);
      fs.rmSync(planFile, { force: true });
    }
    for (const name of ['context', 'numbers', 'list']) {
      assert.doesNotMatch(types[name] ?? '', typeFilter, `${name} should not get the menu item`);
    }
  });

  // Last: it ends the debug session.
  it('one tab per variable: focused when its query is unchanged, refreshed when it changed', async () => {
    const evaluate = async (expression: string) =>
      (await session.customRequest('evaluate', { expression, frameId, context: 'repl' })).result as string;
    const panels: vscode.WebviewPanel[] = [];
    try {
      const first = await visualizeVariable('parameterized');
      assert.ok(first?.kind === 'plan', `expected a plan, got ${JSON.stringify(first)}`);
      const tab = first.panel;
      panels.push(tab);

      // Same variable, same query: the tab is only brought to the front; nothing is generated.
      const pagesBefore = generatedPages();
      const again = await visualizeVariable('parameterized');
      assert.ok(again?.kind === 'unchanged', `expected the same tab unchanged, got ${JSON.stringify(again)}`);
      assert.strictEqual(again.panel, tab);
      assert.deepStrictEqual(generatedPages(), pagesBefore);

      // Another variable gets its own tab, so plans can be compared side by side.
      const other = await visualizeVariable('query');
      assert.ok(other?.kind === 'plan', `expected a plan, got ${JSON.stringify(other)}`);
      panels.push(other.panel);
      assert.notStrictEqual(other.panel, tab);

      // Only a parameter value changes: same tab, refreshed.
      assert.strictEqual(await evaluate('minRating = 5'), '5');
      const changedParameter = await visualizeVariable('parameterized');
      assert.ok(changedParameter?.kind === 'plan', `expected a refreshed plan, got ${JSON.stringify(changedParameter)}`);
      assert.strictEqual(changedParameter.panel, tab, 'the same tab should be refreshed');
      assert.strictEqual(tab.title, 'Query Plan: parameterized');

      // A different query is assigned to the variable: same tab, refreshed.
      await evaluate('parameterized = blogs');
      const newQuery = await visualizeVariable('parameterized');
      assert.ok(newQuery?.kind === 'plan', `expected a refreshed plan, got ${JSON.stringify(newQuery)}`);
      assert.strictEqual(newQuery.panel, tab);
      assert.match(newQuery.html, /SCAN/, 'the DbSet has no filter, so SQLite scans the table');
      assert.strictEqual((await visualizeVariable('parameterized'))?.kind, 'unchanged');

      // The tab's Refresh button gets the plan again even though the query is the same.
      tab.reveal(undefined, false);
      await waitFor(() => (tab.active ? true : undefined), 'the plan tab to become active');
      const refreshed = await vscode.commands.executeCommand<VisualizeResult | undefined>(REFRESH_COMMAND);
      assert.ok(refreshed?.kind === 'plan', `expected a refreshed plan, got ${JSON.stringify(refreshed)}`);
      assert.strictEqual(refreshed.panel, tab);

      // Once the session has ended, the tab stays open but can't be refreshed.
      await vscode.debug.stopDebugging(session);
      await waitFor(() => (vscode.debug.activeDebugSession ? undefined : true), 'the debug session to end');
      tab.reveal(undefined, false);
      await waitFor(() => (tab.active ? true : undefined), 'the plan tab to become active');
      const ended = await vscode.commands.executeCommand<VisualizeResult | undefined>(REFRESH_COMMAND);
      assert.ok(ended?.kind === 'error', `expected an error, got ${JSON.stringify(ended)}`);
      assert.match(ended.message, /debug session of this plan has ended/);
    } finally {
      panels.forEach((p) => p.dispose());
    }
  });
});
