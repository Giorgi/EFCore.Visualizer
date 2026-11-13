// Fits pev2's diagram again after the window grows or shrinks. Loaded by template.html after its own scripts, in
// Visual Studio, VS Code and Rider alike.
//
// pev2 fits the diagram to the window only when it opens, so a plan opened in a narrow tab or window stays small
// after it grows, for example when its tab is maximized. After a resize of more than 15% in either direction, this
// mounts pev2 again, which fits the zoom to the new size; with a node selected, pev2 then centers on that node, as
// it does when a page opens with one. pev2 keeps the selected tab and node in the URL hash and its view options in
// localStorage, so the new mount keeps them; expanded nodes and their detail tabs are noted before the remount and
// opened again after it. It doesn't refit once the user has zoomed or dragged the diagram: that view is the user's
// choice (until the plan is opened again).
//
// It uses the template's globals (Vue, pev2, app, plan, query) and does nothing without them. It depends on pev2's
// markup (.plan-node, .detailed, .node-detail-toggle, the d3-zoom __zoom property), so check it again after
// upgrading pev2; if a newer pev2 fits on resize itself (https://github.com/dalibo/pev2/issues/977), drop it.
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
  function onPlanTab() { return /^(#plan)?($|\/)/.test(location.hash); }
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
