import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { contentSecurityPolicy, HASH_LINKS, REFIT_ON_RESIZE, toWebviewHtml } from '../../webview';

const resourcesDir = path.resolve(__dirname, '../../../dist/Resources');

/** Enough of a webview for toWebviewHtml: these pages link no assets. */
const webview = { cspSource: 'vscode-resource:', asWebviewUri: (uri: vscode.Uri) => uri } as unknown as vscode.Webview;
const csp = `<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(webview.cspSource)}">`;

/** The JavaScript inside a <script>…</script> string. */
const scriptBody = (script: string) => script.replace(/^<script>/, '').replace(/<\/script>$/, '');

describe('page scripts added to the webview', () => {
  it('are valid JavaScript, as the page gets them', () => {
    // They're template strings in TypeScript, so an escape that's wrong there only shows in the page.
    for (const script of [HASH_LINKS, REFIT_ON_RESIZE]) {
      assert.doesNotThrow(() => new Function(scriptBody(script)));
    }
    assert.ok(REFIT_ON_RESIZE.includes('/^(#plan)?($|\\/)/'), 'the Plan tab test must reach the page as /^(#plan)?($|\\/)/');
  });

  it('come after the Postgres page\'s own scripts, whose globals the refit uses', () => {
    // The Postgres template has no <head> or <body>: the scripts go at the end.
    const template = fs.readFileSync(path.join(resourcesDir, 'Postgres', 'template.html'), 'utf-8');
    const html = toWebviewHtml(template, webview, true);
    assert.ok(html.startsWith(csp), 'the CSP <meta> must stay first');
    assert.ok(html.endsWith(HASH_LINKS + REFIT_ON_RESIZE));
    assert.ok(html.lastIndexOf('app.mount("#app")') < html.indexOf(REFIT_ON_RESIZE));
  });

  it('go at the end of the body of a page that has one, and the CSP into its head', () => {
    const page = '<!DOCTYPE html><html><head><title>t</title></head><body><script>var page = 1</script></body></html>';
    const html = toWebviewHtml(page, webview, false);
    assert.ok(html.startsWith(`<!DOCTYPE html><html><head>${csp}<title>t</title>`), html);
    assert.ok(html.endsWith(`<script>var page = 1</script>${HASH_LINKS}${REFIT_ON_RESIZE}</body></html>`), html);
  });

  it('the refit does nothing on pages without pev2', () => {
    // Run where there's no Vue, pev2 or app, like the SQL Server, Oracle and SQLite pages: it must stop at once.
    assert.doesNotThrow(() => new Function(scriptBody(REFIT_ON_RESIZE))());
  });
});
