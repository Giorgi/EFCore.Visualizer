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

/**
 * Content-Security-Policy for the page. The templates use inline scripts and styles, and the Postgres page
 * compiles pev2's Vue template in the browser (which needs 'unsafe-eval'). Everything else is closed: no
 * network access at all, so even injected markup couldn't send the query or plan anywhere.
 */
export function contentSecurityPolicy(cspSource: string): string {
  return [
    "default-src 'none'",
    `script-src ${cspSource} 'unsafe-inline' 'unsafe-eval'`,
    `style-src ${cspSource} 'unsafe-inline'`,
    `img-src ${cspSource} data:`,
    `font-src ${cspSource}`,
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');
}

/** Puts the CSP <meta> before anything else that loads: after <head>, else after the doctype, else first. */
function addCsp(html: string, csp: string): string {
  const meta = `<meta http-equiv="Content-Security-Policy" content="${csp}">`;
  const anchor = /<head(\s[^>]*)?>/i.exec(html) ?? /<!DOCTYPE[^>]*>/i.exec(html);
  return anchor ? html.slice(0, anchor.index + anchor[0].length) + meta + html.slice(anchor.index + anchor[0].length) : meta + html;
}

/**
 * The templates switch to dark mode with prefers-color-scheme (a <link media>, @media rules and, for Bootstrap,
 * matchMedia). In a webview that follows the OS, not the VS Code theme (unless window.systemColorTheme is "auto"),
 * so the extension decides: each "(prefers-color-scheme: dark|light)" in those three places becomes "all" when it
 * matches the VS Code theme and "not all" when it doesn't.
 */
const PREFERS_SCHEME = /(media\s*=\s*"|@media\s+|matchMedia\(\s*["'])\(prefers-color-scheme:\s*(dark|light)\)/gi;

export function isDarkTheme(kind: vscode.ColorThemeKind): boolean {
  return kind === vscode.ColorThemeKind.Dark || kind === vscode.ColorThemeKind.HighContrast;
}

export function toWebviewHtml(html: string, webview: vscode.Webview, dark: boolean): string {
  const withAssets = html.replace(PREFERS_SCHEME, (_, before: string, scheme: string) => before + ((scheme.toLowerCase() === 'dark') === dark ? 'all' : 'not all')).replace(FILE_ASSET, (_, start: string, url: string, end: string) => {
    const uri = webview.asWebviewUri(vscode.Uri.file(fileURLToPath(url.replace(/&amp;/g, '&'))));
    return `${start}${uri.toString(true)}${end}`;
  });

  // Follow the VS Code theme instead of the hard-coded white/black backgrounds of the templates.
  const theme = '<style>:root{--bg-color:var(--vscode-editor-background);--text-color:var(--vscode-editor-foreground);}</style>';
  const themed = /<\/head>/i.test(withAssets) ? withAssets.replace(/<\/head>/i, `${theme}</head>`) : `${withAssets}${theme}`;
  return addCsp(themed, contentSecurityPolicy(webview.cspSource));
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

    const render = () => (panel.webview.html = toWebviewHtml(source, panel.webview, isDarkTheme(vscode.window.activeColorTheme.kind)));
    // Re-render when the theme changes, so the page doesn't keep the old colors.
    const themeListener = vscode.window.onDidChangeActiveColorTheme(render);
    panel.onDidDispose(() => themeListener.dispose());

    const html = render();
    return { panel, html };
  } finally {
    // The page is fully loaded into the webview; nothing references the generated file any more.
    fs.rmSync(filePath, { force: true });
  }
}
