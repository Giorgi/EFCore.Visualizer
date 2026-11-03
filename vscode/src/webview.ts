import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import * as vscode from 'vscode';

/**
 * The helper writes self-contained pages to the temp folder. Their scripts and styles stay in the
 * extension's Resources folder and are linked with absolute file: URLs (<script src>, <link href>).
 * A webview can't load file: URLs, so each one is replaced with the file's webview URI.
 */
const FILE_ASSET = /(<(?:script|link)\b[^>]*?\b(?:src|href)\s*=\s*")(file:[^"]*)(")/gi;

/** Local files the page's <script src> and <link href> point to. */
export function assetFiles(html: string): string[] {
  return [...html.matchAll(FILE_ASSET)].map((m) => fileURLToPath(m[2].replace(/&amp;/g, '&')));
}

export function toWebviewHtml(html: string, webview: vscode.Webview): string {
  const withAssets = html.replace(FILE_ASSET, (_, start: string, url: string, end: string) => {
    const uri = webview.asWebviewUri(vscode.Uri.file(fileURLToPath(url.replace(/&amp;/g, '&'))));
    return `${start}${uri.toString(true)}${end}`;
  });

  // Follow the VS Code theme instead of the hard-coded white/black backgrounds of the templates.
  const theme = '<style>:root{--bg-color:var(--vscode-editor-background);--text-color:var(--vscode-editor-foreground);}</style>';
  return /<\/head>/i.test(withAssets) ? withAssets.replace(/<\/head>/i, `${theme}</head>`) : `${withAssets}${theme}`;
}

export function showHtmlFile(filePath: string, title: string): { panel: vscode.WebviewPanel; html: string } {
  try {
    const source = fs.readFileSync(filePath, 'utf-8');
    // The webview may read only the folders the page's assets are in.
    const assetFolders = [...new Set(assetFiles(source).map((f) => path.dirname(f)))];

    const panel = vscode.window.createWebviewPanel('efcoreVisualizer.queryPlan', title, vscode.ViewColumn.Beside, {
      enableScripts: true,
      localResourceRoots: assetFolders.map((f) => vscode.Uri.file(f)),
      retainContextWhenHidden: true,
    });

    const html = toWebviewHtml(source, panel.webview);
    panel.webview.html = html;
    return { panel, html };
  } finally {
    // The page is fully loaded into the webview; nothing references the generated file any more.
    fs.rmSync(filePath, { force: true });
  }
}
