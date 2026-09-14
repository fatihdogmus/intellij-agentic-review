package dev.fatihdogmus.agenticreview

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

@TestApplication
class ReviewStripeToolWindowFactoryTest {
    private val project by projectFixture()

    @Test
    fun iconIsNotNull() {
        val factory = ReviewStripeToolWindowFactory()
        assertThat(factory.icon).isNotNull
    }

    @Test
    fun factoryIsDumbAware() {
        val factory = ReviewStripeToolWindowFactory()
        assertThat(factory).isInstanceOf(com.intellij.openapi.project.DumbAware::class.java)
    }

    @Test
    fun factoryImplementsToolWindowFactory() {
        val factory = ReviewStripeToolWindowFactory()
        assertThat(factory).isInstanceOf(com.intellij.openapi.wm.ToolWindowFactory::class.java)
    }

    /**
     * The factory instance is shared IDE-wide, so [ReviewStripeToolWindowFactory.createToolWindowContent] runs once
     * per tool window but many times per IDE session: once for every project, and again whenever the tool window is
     * re-registered. Each call must scope its message bus connection to a disposable that belongs to *that* tool
     * window. Parenting to a shared instance - as a non-capturing `Disposable {}` SAM lambda silently does, because
     * the JVM caches one instance per call site - makes the second call register under an already disposed parent and
     * throw `IncorrectOperationException`.
     */
    @Test
    fun contentCanBeCreatedAgainAfterAnEarlierToolWindowWasDisposed() {
        val factory = ReviewStripeToolWindowFactory()

        val first = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        assertThat(first.disposable)
            .describedAs("a tool window must own its disposable, otherwise this test would tear down the project")
            .isNotSameAs(project)
        factory.createToolWindowContent(project, first)
        Disposer.dispose(first.disposable)

        val second = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        assertThatCode { factory.createToolWindowContent(project, second) }.doesNotThrowAnyException()
        Disposer.dispose(second.disposable)
    }
}
