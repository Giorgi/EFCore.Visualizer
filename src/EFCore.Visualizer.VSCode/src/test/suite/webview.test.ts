import * as assert from 'assert';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { contentSecurityPolicy, HASH_LINKS, toWebviewHtml } from '../../webview';

const resourcesDir = path.resolve(__dirname, '../../../dist/Resources');

/** Enough of a webview for toWebviewHtml: these pages link no assets. */
const webview = { cspSource: 'vscode-resource:', asWebviewUri: (uri: vscode.Uri) => uri } as unknown as vscode.Webview;
const csp = `<meta http-equiv="Content-Security-Policy" content="${contentSecurityPolicy(webview.cspSource)}">`;

/** The JavaScript inside a <script>…</script> string. */
const scriptBody = (script: string) => script.replace(/^<script>/, '').replace(/<\/script>$/, '');

describe('page scripts added to the webview', () => {
  it('are valid JavaScript, as the page gets them', () => {
    // A template string in TypeScript, so an escape that's wrong there only shows in the page.
    assert.doesNotThrow(() => new Function(scriptBody(HASH_LINKS)));
  });

  it('go at the end of the Postgres page, which has no <head> or <body>', () => {
    const template = fs.readFileSync(path.join(resourcesDir, 'Postgres', 'template.html'), 'utf-8');
    const html = toWebviewHtml(template, webview, true);
    assert.ok(html.startsWith(csp), 'the CSP <meta> must stay first');
    assert.ok(html.endsWith(HASH_LINKS));
  });

  it('go at the end of the body of a page that has one, and the CSP into its head', () => {
    const page = '<!DOCTYPE html><html><head><title>t</title></head><body><script>var page = 1</script></body></html>';
    const html = toWebviewHtml(page, webview, false);
    assert.ok(html.startsWith(`<!DOCTYPE html><html><head>${csp}<title>t</title>`), html);
    assert.ok(html.endsWith(`<script>var page = 1</script>${HASH_LINKS}</body></html>`), html);
  });
});

// Shared with Visual Studio and Rider: the Postgres template loads it itself.
describe('the Postgres page\'s refit.js', () => {
  const template = () => fs.readFileSync(path.join(resourcesDir, 'Postgres', 'template.html'), 'utf-8');
  const refit = () => fs.readFileSync(path.join(resourcesDir, 'Postgres', 'refit.js'), 'utf-8');

  it('is loaded after the template\'s own scripts, whose globals it uses', () => {
    const page = template();
    assert.ok(page.lastIndexOf('app.mount("#app")') < page.indexOf('<script src="refit.js">'), 'refit.js must come after app.mount');
  });

  it('is valid JavaScript, with the Plan tab test as the page needs it', () => {
    assert.doesNotThrow(() => new Function(refit()));
    assert.ok(refit().includes('/^(#plan)?($|\\/)/'), 'the Plan tab test must be /^(#plan)?($|\\/)/');
  });

  it('does nothing on pages without pev2', () => {
    // Run where there's no Vue, pev2 or app, like the SQL Server, Oracle and SQLite pages: it must stop at once.
    assert.doesNotThrow(() => new Function(refit())());
  });
});
