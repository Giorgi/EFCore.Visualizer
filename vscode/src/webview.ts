import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

/**
 * The helper writes self-contained pages into <Resources>/<Provider>/xxxx.html that reference
 * sibling assets relatively (e.g. "qp.css", "../Common/highlight.min.js", "..\Common\treeflex.css").
 * A <base> pointing at the page's folder as a webview URI makes all of those resolve inside the webview
 * (backslashes are normalised to "/" for https URLs, which webview resource URIs are).
 */
export function toWebviewHtml(html: string, baseUri: vscode.Uri): string {
  const base = `<base href="${baseUri.toString(true).replace(/\/?$/, '/')}">`;
  // Follow the VS Code theme instead of the hard-coded white/black backgrounds of the templates.
  const theme = '<style>:root{--bg-color:var(--vscode-editor-background);--text-color:var(--vscode-editor-foreground);}</style>';

  const head = /<head[^>]*>/i.exec(html);
  let result = head ? html.replace(head[0], `${head[0]}${base}`) : `${base}${html}`;
  result = /<\/head>/i.test(result) ? result.replace(/<\/head>/i, `${theme}</head>`) : `${result}${theme}`;
  return result;
}

export function showHtmlFile(filePath: string, title: string): { panel: vscode.WebviewPanel; html: string } {
  const pageDir = path.dirname(filePath);
  const resourcesRoot = path.dirname(pageDir); // templates reach into ../Common

  const panel = vscode.window.createWebviewPanel('efcoreVisualizer.queryPlan', title, vscode.ViewColumn.Beside, {
    enableScripts: true,
    localResourceRoots: [vscode.Uri.file(resourcesRoot)],
    retainContextWhenHidden: true,
  });

  try {
    const html = toWebviewHtml(fs.readFileSync(filePath, 'utf-8'), panel.webview.asWebviewUri(vscode.Uri.file(pageDir)));
    panel.webview.html = html;
    return { panel, html };
  } finally {
    // The page is fully loaded into the webview; nothing references the generated file any more.
    fs.rmSync(filePath, { force: true });
  }
}
