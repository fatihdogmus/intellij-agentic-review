package dev.fatihdogmus.agenticreview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import javax.swing.Icon
import javax.swing.JPanel

class ReviewStripeToolWindowFactory : ToolWindowFactory, DumbAware {
    override val icon: Icon = IconLoader.getIcon("/icons/review.svg", ReviewStripeToolWindowFactory::class.java)

    /**
     * The platform consumes the registered content factory on first use, so this runs at most once per tool window -
     * which makes the [ToolWindowManagerListener] subscription below the only path that reacts to every stripe click
     * after the first one.
     *
     * The subscription is parented to [ToolWindow.getDisposable] rather than to a disposable created here: a single
     * factory instance is shared across every open project, so anything allocated per call has to be a genuinely
     * fresh instance. A non-capturing `Disposable {}` SAM lambda is not - the JVM caches one instance per call site -
     * and reusing it across projects made this method register under an already disposed parent.
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contentCount == 0) {
            val content = toolWindow.contentManager.factory.createContent(JPanel(), "", false)
            toolWindow.contentManager.addContent(content)

            project.messageBus.connect(toolWindow.disposable)
                .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                    override fun toolWindowShown(shownToolWindow: ToolWindow) {
                        if (shownToolWindow.id != toolWindow.id) return
                        openReviewAndHide(project, shownToolWindow)
                    }
                })
        }

        openReviewAndHide(project, toolWindow)
    }

    private fun openReviewAndHide(project: Project, toolWindow: ToolWindow) {
        ToolWindowManager.getInstance(project).invokeLater {
            hideOtherSideToolWindows(project, toolWindow.id)
            ReviewManagerService.getInstance(project).openDefaultReview()
            if (toolWindow.isVisible) {
                toolWindow.hide()
            }
        }
    }

    private fun hideOtherSideToolWindows(project: Project, reviewToolWindowId: String) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        toolWindowManager.toolWindowIds
            .asSequence()
            .filter { it != reviewToolWindowId }
            .mapNotNull(toolWindowManager::getToolWindow)
            .filter { it.isVisible && (it.anchor == ToolWindowAnchor.LEFT || it.anchor == ToolWindowAnchor.RIGHT) }
            .forEach { it.hide() }
    }
}
