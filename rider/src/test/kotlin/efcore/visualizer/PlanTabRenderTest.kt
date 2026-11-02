package efcore.visualizer

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import com.jetbrains.rider.debugger.settings.DotNetDebuggerSettings
import com.jetbrains.rider.test.annotations.Solution
import com.jetbrains.rider.test.annotations.TestSettings
import com.jetbrains.rider.test.base.DebuggerTestBase
import com.jetbrains.rider.test.debugger.disableTargetInvokeWithWatches
import com.jetbrains.rider.test.debugger.enableTargetInvokeWithWatches
import com.jetbrains.rider.test.enums.BuildTool
import com.jetbrains.rider.test.enums.Mono
import com.jetbrains.rider.test.enums.sdk.SdkVersion
import com.jetbrains.rider.test.scriptingApi.resumeSession
import com.jetbrains.rider.test.scriptingApi.toggleBreakpoint
import com.jetbrains.rider.test.scriptingApi.waitForPause
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandlerAdapter
import org.testng.annotations.AfterMethod
import org.testng.SkipException
import org.testng.annotations.Test
import java.util.concurrent.CompletableFuture
import kotlin.io.path.deleteIfExists
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Risk 2 from the spike: does the plan tab really render in Rider's embedded browser (JCEF)?
 * Runs the real action with the real presenter, then asks the loaded page (from inside) whether the external
 * stylesheet (reached through the template's "..\Common\treeflex.css" path) applied and whether its script runs.
 *
 * Chromium needs an X display even off-screen: run under xvfb-run (skipped otherwise). The test framework's editor
 * manager cannot host HTML editor tabs, so the URL the presenter would open is loaded into a JBCefBrowser directly
 * (the same browser type HTMLEditorProvider's tab uses). Screenshots are not taken: in this off-screen test mode
 * Chromium keeps a 300x200 default surface, which makes captures misleading.
 */
@TestSettings(sdkVersion = SdkVersion.AUTODETECT, buildTool = BuildTool.AUTODETECT, mono = Mono.NONE)
@Solution("SampleApp")
class PlanTabRenderTest : DebuggerTestBase() {
    override val projectName = "SampleApp"

    @Test
    fun planTabRendersWithStylesAndScript() {
        if (System.getenv("DISPLAY").isNullOrEmpty()) throw SkipException("JCEF needs an X display; run under xvfb-run")
        val programCs = activeSolutionDirectory.resolve("SampleApp/Program.cs").toFile()
        val line = programCs.readLines().indexOfFirst { "// BREAKPOINT" in it } + 1

        testDebugProgram({
            DotNetDebuggerSettings.instance.enableTargetInvokeWithWatches()
            toggleBreakpoint(project, "SampleApp/Program.cs", line)
        }, {
            waitForPause()
            assertTrue(JBCefApp.isSupported(), "JCEF is not supported in this test environment")

            // Real Variables view -> select "query" -> real action; capture what the presenter is asked to show.
            val tree = assertNotNull((session as XDebugSessionImpl).sessionTabIfInitialized?.variablesView?.tree)
            var node: XValueNodeImpl? = null
            PlatformTestUtil.waitWithEventsDispatching("'query' not in Variables view", {
                node = (tree.root as? XValueContainerNode<*>)?.children?.filterIsInstance<XValueNodeImpl>()?.firstOrNull { it.name == "query" }
                node != null
            }, 60)
            tree.selectionPath = node!!.path
            val action = ActionManager.getInstance().getAction(VisualizeQueryPlanAction.ID)
            val shown = CompletableFuture<VisualizeResult>()
            val disposable = Disposer.newDisposable()
            ApplicationManager.getApplication().replaceService(QueryPlanPresenter::class.java, object : QueryPlanPresenter() {
                override fun show(project: Project, result: VisualizeResult, expression: String) { shown.complete(result) }
                override fun notify(project: Project, message: String, type: NotificationType) { shown.completeExceptionally(AssertionError(message)) }
            }, disposable)
            action.actionPerformed(TestActionEvent.createTestEvent(action, Utils.createAsyncDataContext(tree)))
            val result = PlatformTestUtil.waitForFuture(shown, 60_000)
            Disposer.dispose(disposable)

            // Load the very URL QueryPlanPresenter hands to HTMLEditorProvider into a real JCEF browser
            // (the test framework's editor manager cannot host HTML editor tabs).
            val url = result.file.toUri().toString()
            println("Loading $url")
            val browser = JBCefBrowser.createBuilder().setUrl(url).setOffScreenRendering(true).setCreateImmediately(true).build()
            val cef = browser.cefBrowser
            // Off-screen and never shown: size the whole component tree (Chromium asks the inner OSR component for its
            // view rect), then tell it to re-layout; otherwise it renders into a tiny default surface.
            fun sizeAll(c: java.awt.Component) {
                c.setBounds(0, 0, 1200, 700)
                (c as? java.awt.Container)?.let { it.doLayout(); it.components.forEach(::sizeAll) }
            }
            sizeAll(browser.component)
            cef.wasResized(1200, 700)

            // Read results back through the page title (no JS bridge needed).
            var title = ""
            browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onTitleChange(b: CefBrowser?, t: String?) { if (t?.startsWith("EFV:") == true) title = t.removePrefix("EFV:") }
            }, cef)
            val probe = """
                if (document.readyState === 'complete' && document.querySelector('.tf-nc')) {
                  const nodes = [...document.querySelectorAll('.tf-nc')];
                  const target = nodes[1];
                  target.click();
                  const collapsed = target.parentElement.classList.contains('collapsed');
                  target.click();
                  document.title = 'EFV:' + JSON.stringify({
                    url: location.href,
                    liFlexDirection: getComputedStyle(document.querySelector('.tf-tree li')).flexDirection,
                    nodes: nodes.map(n => n.textContent.trim()),
                    scriptCollapsesNodes: collapsed
                  });
                }
            """.trimIndent()
            var lastProbe = 0L
            PlatformTestUtil.waitWithEventsDispatching("plan page did not finish loading", {
                if (System.currentTimeMillis() - lastProbe > 500) {
                    lastProbe = System.currentTimeMillis()
                    cef.executeJavaScript(probe, cef.url, 0)
                }
                title.isNotEmpty()
            }, 60)
            println("Page report: $title")

            // Only treeflex.css (linked as "..\Common\treeflex.css") sets this; a plain li would be "row".
            assertContains(title, "\"liFlexDirection\":\"column\"")
            assertContains(title, "IX_Blogs_Rating")                           // real plan content
            assertContains(title, "\"scriptCollapsesNodes\":true")            // template's JS runs

            Disposer.dispose(browser)
            result.file.deleteIfExists()
            resumeSession()
        }, true)
    }

    @AfterMethod(alwaysRun = true)
    fun restoreDebuggerSettings() {
        DotNetDebuggerSettings.instance.disableTargetInvokeWithWatches()
    }
}
