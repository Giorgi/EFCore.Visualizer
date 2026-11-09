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

/**
 * VS Code's webview handles every click on a link itself: for a link to "#something" it only scrolls to the element
 * with that id and cancels the click, so the URL hash never changes. pev2's tabs (Plan, Grid, Raw, ...) and node
 * links are such links, and pev2 switches on the hashchange event. This listener sets the hash itself. It's on
 * `document`, so it runs after the page's own handlers and before VS Code's, which is on `window`: a click the page
 * already cancelled, or stopped (pev2's node links in the Grid tab navigate natively that way), is left alone.
 * Links to "" or "#" are the page's own buttons (pev2's detail tabs and sort links) and are skipped too.
 */
export const HASH_LINKS = `<script>
document.addEventListener("click", function (e) {
  if (e.defaultPrevented) return;
  var link = e.target instanceof Element && e.target.closest("a[href^='#']");
  var hash = link && link.getAttribute("href");
  if (!hash || hash === "#") return;
  e.preventDefault();
  if (location.hash !== hash) location.hash = hash;
});
</script>`;

/**
 * pev2 fits the diagram to the window only when it opens, so a plan opened in a narrow tab stays small after the
 * tab grows, for example when its group is maximized. After a resize of more than 15% in either direction, this
 * mounts pev2 again, which fits the zoom to the new size; with a node selected, pev2 then centers on that node, as
 * it does when a page opens with one. pev2 keeps the selected tab and node in the URL hash and its view options in
 * localStorage, so the new mount keeps them; expanded nodes and their detail tabs are noted before the remount and
 * opened again after it. It doesn't refit once the user has zoomed or dragged the diagram: that view is the user's
 * choice (until the plan is opened again).
 *
 * It must come after the page's own scripts: it uses the Postgres template's globals (Vue, pev2, app, plan, query),
 * and does nothing on pages without them. The template itself is shared with Visual Studio and stays unchanged.
 * It depends on pev2's markup (.plan-node, .detailed, .node-detail-toggle, the d3-zoom __zoom property), so check it
 * again after upgrading pev2.
 */
export const REFIT_ON_RESIZE = `<script>
(function () {
  if (typeof Vue === "undefined" || typeof pev2 === "undefined" || typeof app === "undefined") return;
  var RESIZE_RATIO = 0.15;
  var DELAY_MS = 100; // wait for the end of a resize, such as dragging a sash
  var MAX_FRAMES = 60; // how long to wait for the new mount to draw its nodes
  var current = app;
  var fitted = { width: innerWidth, height: innerHeight };
  var userMoved = false;
  var timer, down;
  // Each remount gets a number. A re-expansion still running for an older mount stops, and the newer mount takes
  // over its list (pendingNodes), so a second remount before the nodes are open again doesn't lose them.
  var generation = 0;
  var pendingNodes;

  // Which nodes are expanded, and the detail tab each shows, lives in pev2's components, so a remount would collapse
  // them. They're noted before it (a card with the "detailed" class) and opened again after it.
  function expandedNodes() {
    return [].slice.call(document.querySelectorAll(".plan-node.detailed")).map(function (card) {
      var link = card.querySelector("a[href^='#plan/node/']");
      var tab = card.querySelector(".nav-link.active");
      return { href: link && link.getAttribute("href"), tab: tab && tab.textContent.trim() };
    }).filter(function (node) { return node.href; });
  }
  function card(href) {
    var link = [].slice.call(document.querySelectorAll(".plan-node a[href^='#plan/node/']")).filter(function (a) {
      return a.getAttribute("href") === href;
    })[0];
    return link && link.closest(".plan-node");
  }
  function openTab(href, name, gen) {
    if (gen !== generation) return;
    var c = card(href);
    if (!c) return;
    [].slice.call(c.querySelectorAll(".nav-link")).forEach(function (tab) {
      if (tab.textContent.trim() === name && !tab.classList.contains("active")) tab.click();
    });
  }
  function expandAgain(nodes, gen, framesLeft) {
    if (gen !== generation) return;
    // The diagram is drawn a few frames after the mount.
    if (nodes.length && !card(nodes[0].href) && framesLeft > 0) {
      requestAnimationFrame(function () { expandAgain(nodes, gen, framesLeft - 1); });
      return;
    }
    pendingNodes = undefined;
    nodes.forEach(function (node) {
      var c = card(node.href);
      var toggle = c && c.querySelector(".node-detail-toggle");
      if (!toggle || c.classList.contains("detailed")) return;
      toggle.click();
      if (node.tab) requestAnimationFrame(function () { openTab(node.href, node.tab, gen); });
    });
  }

  function remount() {
    var nodes = pendingNodes || expandedNodes();
    pendingNodes = nodes;
    generation++;
    current.unmount();
    current = Vue.createApp({
      data: function () { return { plan: plan, query: query }; },
      template: '<pev2 :plan-source="plan" :plan-query="query" />',
    });
    current.component("pev2", pev2.Plan);
    current.mount("#app");
    expandAgain(nodes, generation, MAX_FRAMES);
  }
  function onPlanTab() { return /^(#plan)?($|\\/)/.test(location.hash); }
  // d3-zoom keeps its state in a __zoom property on the element the zoom is bound to: the diagram.
  function inDiagram(target) {
    for (var node = target; node; node = node.parentNode) if (node.__zoom) return true;
    return false;
  }
  function changed(now, before) { return Math.abs(now - before) > before * RESIZE_RATIO; }

  // The user zoomed or panned: the wheel or a double-click anywhere on the diagram, or a drag with the main button
  // on its background. pev2 doesn't pan when a drag starts on a node card (for example to select its text).
  addEventListener("wheel", function (e) { if (inDiagram(e.target)) userMoved = true; }, { capture: true, passive: true });
  addEventListener("dblclick", function (e) { if (inDiagram(e.target)) userMoved = true; }, true);
  addEventListener("pointerdown", function (e) {
    var onBackground = e.button === 0 && inDiagram(e.target) && !(e.target instanceof Element && e.target.closest(".plan-node"));
    down = onBackground ? { x: e.clientX, y: e.clientY } : undefined;
  }, true);
  addEventListener("pointerup", function () { down = undefined; }, true);
  addEventListener("pointercancel", function () { down = undefined; }, true);
  addEventListener("pointermove", function (e) {
    // A context menu can take the pointer before pointerup: count it only while the main button is still held.
    if (down && (e.buttons & 1) && Math.hypot(e.clientX - down.x, e.clientY - down.y) > 4) userMoved = true;
  }, true);

  function refitIfResized() {
    if (userMoved || !onPlanTab()) return;
    // A hidden tab, or one with no size yet: the next resize, once it's shown, refits.
    if (document.hidden || !innerWidth || !innerHeight) return;
    if (!changed(innerWidth, fitted.width) && !changed(innerHeight, fitted.height)) return;
    fitted = { width: innerWidth, height: innerHeight };
    remount();
  }
  function refitSoon() {
    clearTimeout(timer);
    timer = setTimeout(refitIfResized, DELAY_MS);
  }

  addEventListener("resize", refitSoon);
  // A resize while another tab (Grid, Raw, ...) is shown waits until the Plan tab is back: a remount would reset
  // that tab's scrolling, and pev2 doesn't fit the diagram again when its tab is chosen. Not during the hashchange
  // itself: pev2 handles it too, with a timer (selecting the node of a #plan/node/N link) that must still find the
  // instance it belongs to.
  var wasOnPlanTab = onPlanTab();
  addEventListener("hashchange", function () {
    var now = onPlanTab();
    if (now && !wasOnPlanTab) refitSoon();
    wasOnPlanTab = now;
  });
})();
</script>`;

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
  // At the end of the body, after the page's own scripts: REFIT_ON_RESIZE uses their globals.
  const scripts = HASH_LINKS + REFIT_ON_RESIZE;
  const bodyEnd = themed.search(/<\/body>/i);
  const withScripts = bodyEnd >= 0 ? themed.slice(0, bodyEnd) + scripts + themed.slice(bodyEnd) : themed + scripts;
  return addCsp(withScripts, contentSecurityPolicy(webview.cspSource));
}

export const PLAN_VIEW_TYPE = 'efcoreVisualizer.queryPlan';

/**
 * A plan tab. It can be filled again with a new page (a refreshed plan), and it re-renders when the
 * VS Code theme changes.
 */
export class PlanView {
  readonly panel: vscode.WebviewPanel;
  private source = '';

  /** `column`: where the tab opens; beside the active editor if not given. */
  constructor(title: string, column: vscode.ViewColumn = vscode.ViewColumn.Beside) {
    this.panel = vscode.window.createWebviewPanel(PLAN_VIEW_TYPE, title, column, {
      enableScripts: true,
      localResourceRoots: [],
      retainContextWhenHidden: true,
    });
    // Re-render when the theme changes, so the page doesn't keep the old colors.
    const themeListener = vscode.window.onDidChangeActiveColorTheme(() => this.render());
    this.panel.onDidDispose(() => themeListener.dispose());
  }

  /** Shows the generated page `filePath` (and deletes it), with `title` as the tab's title. Returns the webview HTML. */
  show(filePath: string, title: string): string {
    try {
      this.source = fs.readFileSync(filePath, 'utf-8');
    } finally {
      // The page is fully loaded; nothing references the generated file any more.
      fs.rmSync(filePath, { force: true });
    }
    this.panel.title = title;
    // The webview may read only the folders the page's assets are in.
    const assetFolders = [...new Set(assetFiles(this.source).map((f) => path.dirname(f)))];
    this.panel.webview.options = { enableScripts: true, localResourceRoots: assetFolders.map((f) => vscode.Uri.file(f)) };
    return this.render();
  }

  private render(): string {
    return (this.panel.webview.html = toWebviewHtml(this.source, this.panel.webview, isDarkTheme(vscode.window.activeColorTheme.kind)));
  }
}
