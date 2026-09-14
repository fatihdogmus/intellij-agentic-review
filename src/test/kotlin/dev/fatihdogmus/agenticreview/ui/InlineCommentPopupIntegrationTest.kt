package dev.fatihdogmus.agenticreview.ui

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.diff.ReviewDiffRequestData
import dev.fatihdogmus.agenticreview.model.CommentStatus
import dev.fatihdogmus.agenticreview.model.DiffSide
import dev.fatihdogmus.agenticreview.model.ReplyAuthorKind
import dev.fatihdogmus.agenticreview.model.ReplyKind
import dev.fatihdogmus.agenticreview.model.Review
import dev.fatihdogmus.agenticreview.model.ReviewTarget
import dev.fatihdogmus.agenticreview.model.ReviewTargetType
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.testutil.findComponents
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import com.intellij.ui.SimpleColoredComponent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

@TestApplication
class InlineCommentPopupIntegrationTest {
    private val project by projectFixture()

    @Test
    fun submittingInlineCommentReplacesFormWithVisibleCommentInlay() {
        val review = seededReview("inline-add")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showInlineCommentForm(project, editor, 0, ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT))
                assertThat(editor.commentInlayComponents()).hasSize(1)

                val form = editor.commentInlayComponents().single()
                findComponents(form).filterIsInstance<JTextArea>().single().text = "Needs fix"
                findComponents(form).filterIsInstance<JButton>().single { it.text == "Comment" }.doClick()

                assertThat(ReviewManagerService.getInstance(project).commentsForFile(review.id, changedFile.filePath))
                    .singleElement()
                    .extracting("body")
                    .isEqualTo("Needs fix")
                assertThat(editor.commentInlayComponents()).hasSize(1)
                assertThat(
                    findComponents(editor.commentInlayComponents().single()).filterIsInstance<JTextArea>()
                        .map { it.text })
                    .contains("Needs fix")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun resolveCommentReturnsWhetherStatusChangedAndKeepsCommentListed() {
        val review = seededReview("resolve-keeps")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val comment =
            ReviewManagerService.getInstance(project).addComment(review.id, changedFile, DiffSide.RIGHT, 1, "todo")!!

        assertThat(resolveComment(project, comment.id)).isTrue()
        assertThat(ReviewManagerService.getInstance(project).commentsForFile(review.id, changedFile.filePath))
            .singleElement()
            .extracting("status")
            .isEqualTo(CommentStatus.RESOLVED)

        assertThat(resolveComment(project, comment.id)).isFalse()
        assertThat(resolveComment(project, "missing")).isFalse()
    }

    @Test
    fun reopenCommentReturnsWhetherStatusChanged() {
        val review = seededReview("reopen-helper")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val comment =
            ReviewManagerService.getInstance(project).addComment(review.id, changedFile, DiffSide.RIGHT, 1, "todo")!!

        assertThat(reopenComment(project, comment.id)).isFalse()
        assertThat(resolveComment(project, comment.id)).isTrue()
        assertThat(reopenComment(project, comment.id)).isTrue()
        assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
        assertThat(reopenComment(project, "missing")).isFalse()
    }

    @Test
    fun deleteCommentAndDismissOnlyDismissesAfterStateChanges() {
        val review = seededReview("delete-dismiss")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val comment =
            ReviewManagerService.getInstance(project).addComment(review.id, changedFile, DiffSide.RIGHT, 1, "todo")!!
        var dismissCount = 0

        assertThat(deleteCommentAndDismiss(project, comment.id) { dismissCount++ }).isTrue()
        assertThat(dismissCount).isEqualTo(1)
        assertThat(ReviewManagerService.getInstance(project).commentsForFile(review.id, changedFile.filePath)).isEmpty()

        assertThat(deleteCommentAndDismiss(project, "missing") { dismissCount++ }).isFalse()
        assertThat(dismissCount).isEqualTo(1)
    }

    @Test
    fun existingCommentInlayRendersRepliesAndAcceptsNewReply() {
        val review = seededReview("inline-reply")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")
        manager.addReply(comment.id, "guarded at L42", author = "codex", authorKind = ReplyAuthorKind.AGENT)

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()

                assertThat(findComponents(panel).filterIsInstance<JTextArea>().map { it.text })
                    .contains("guarded at L42")

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                findComponents(panel).filterIsInstance<JTextArea>().last().text = "thanks, keeping it"
                val postReplyButton = findComponents(panel).filterIsInstance<JButton>()
                    .single { it.text == "Post reply" }
                postReplyButton.doClick()

                assertThat(comment.replies.map { it.body })
                    .containsExactly("guarded at L42", "thanks, keeping it")
                assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
                assertThat(
                    findComponents(panel).filterIsInstance<JTextArea>().filter { !it.isEditable }.map { it.text })
                    .contains("thanks, keeping it")
                assertThat(isEffectivelyVisible(postReplyButton)).isFalse()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun createReplyBlockShowsCheckIconOnlyForResolutionReplies() {
        val review = seededReview("reply-icon")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")
        manager.addReply(
            comment.id, "just a note", author = "you", authorKind = ReplyAuthorKind.HUMAN, kind = ReplyKind.COMMENT,
        )
        manager.addReply(
            comment.id, "fixed in a1b2c3", author = "codex", authorKind = ReplyAuthorKind.AGENT,
            kind = ReplyKind.RESOLUTION,
        )

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()
                val labels = findComponents(panel).filterIsInstance<JLabel>()

                val commentReplyLabel = labels.single { it.text.startsWith("you") }
                val resolutionReplyLabel = labels.single { it.text.startsWith("codex") }

                assertThat(commentReplyLabel.icon).isNull()
                assertThat(resolutionReplyLabel.icon).isNotNull()

                // The attribution line must render the stored ISO timestamp as
                // "yyyy-MM-dd HH:mm:ss", not leak the raw ISO-8601 string into the UI.
                assertThat(commentReplyLabel.text)
                    .matches("""you · \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
                    .doesNotContain("T")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun showReplyFormResetsStaleTextOnReopen() {
        val review = seededReview("reply-stale-text")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                findComponents(panel).filterIsInstance<JTextArea>().last().text = "half-typed thought"
                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Cancel reply" }.doClick()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                val reopenedReplyInput = findComponents(panel).filterIsInstance<JTextArea>().last()

                assertThat(reopenedReplyInput.text).isEmpty()
                assertThat(comment.replies).isEmpty()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun clickingReplyAgainWhileComposingPreservesDraftText() {
        val review = seededReview("reply-preserve-draft")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                findComponents(panel).filterIsInstance<JTextArea>().last().text = "half-typed thought"

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                val stillOpenReplyInput = findComponents(panel).filterIsInstance<JTextArea>().last()

                assertThat(stillOpenReplyInput.text).isEqualTo("half-typed thought")
                assertThat(comment.replies).isEmpty()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun submitShortcutPostsReply() {
        val review = seededReview("reply-submit-shortcut")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                val replyTextArea = findComponents(panel).filterIsInstance<JTextArea>().single { it.isEditable }
                replyTextArea.text = "shortcut reply"

                replyTextArea.actionMap.get("localReview.submitComment").actionPerformed(null)

                assertThat(comment.replies.map { it.body }).containsExactly("shortcut reply")
                assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun cancelReplyButtonHidesReplyFormAndLeavesNoReply() {
        val review = seededReview("cancel-reply")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()
                val postReplyButton = findComponents(panel).filterIsInstance<JButton>()
                    .single { it.text == "Post reply" }

                assertThat(isEffectivelyVisible(postReplyButton)).isFalse()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Reply" }.doClick()
                assertThat(isEffectivelyVisible(postReplyButton)).isTrue()

                findComponents(panel).filterIsInstance<JButton>().single { it.text == "Cancel reply" }.doClick()
                assertThat(isEffectivelyVisible(postReplyButton)).isFalse()

                assertThat(comment.replies).isEmpty()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun commentActionsMenuButtonWiresRealGroupWithEditResolveAndDeleteActions() {
        val review = seededReview("actions-group")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix") ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()

                // Only the real button click populates this group; a mutated button that builds a
                // different/empty group would leave commentActionsGroupForTest() reflecting that mutation.
                val group = openCommentActionsGroup(panel)

                assertThat(group.getChildActionsOrStubs().map { it.templateText })
                    .containsExactlyInAnyOrder("Edit comment", "Resolve comment", "Delete comment")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun editActionInvokedThroughRealGroupEntersEditableMode() {
        val review = seededReview("edit-action")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix")
            ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()
                val bodyArea = findComponents(panel).filterIsInstance<JTextArea>().single { it.text == comment.body }
                assertThat(bodyArea.isEditable).isFalse()
                assertThat(isEffectivelyVisible(buttons(panel).single { it.text == "Save" })).isFalse()

                val editAction = openCommentActionsGroup(panel).getChildActionsOrStubs()
                    .single { it.templateText == "Edit comment" }
                editAction.actionPerformed(TestActionEvent.createTestEvent(editAction))

                // enterEditMode()'s only call site is this action; a mutation that empties its body would
                // leave the text area read-only and the Save/Cancel row hidden.
                assertThat(bodyArea.isEditable).isTrue()
                assertThat(isEffectivelyVisible(buttons(panel).single { it.text == "Save" })).isTrue()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun deleteActionInvokedThroughRealGroupRemovesCommentAndDisposesInlay() {
        val review = seededReview("delete-action")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, "needs fix") ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(
                    project,
                    editor,
                    ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT),
                )
                val panel = editor.commentInlayComponents().single()
                val inlay = editor.singleCommentInlay()

                val deleteAction = openCommentActionsGroup(panel).getChildActionsOrStubs()
                    .single { it.templateText == "Delete comment" }
                deleteAction.actionPerformed(TestActionEvent.createTestEvent(deleteAction))

                // deleteCommentAndDismiss is otherwise only exercised as a free function with a fake
                // dismiss lambda; this proves the action really binds it to the panel's own ::dismiss.
                assertThat(manager.commentsForFile(review.id, changedFile.filePath)).isEmpty()
                assertThat(inlay.isValid).isFalse()
                assertThat(editor.commentInlayComponents()).isEmpty()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    private val longBody = buildString {
        append("First paragraph of a long comment that keeps going. ")
        repeat(6) { append("Sentence number $it explaining something in detail. ") }
        append("\n\nSecond paragraph after a blank line. FINAL-MARKER")
    }

    private fun resolvedCommentInEditor(
        suffix: String,
        body: String = longBody,
        replyBody: String? = "done — see AuthHelper.kt",
    ): Triple<EditorEx, JPanel, dev.fatihdogmus.agenticreview.model.ReviewComment> {
        val review = seededReview(suffix)
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, body) ?: error("comment missing")
        manager.markCommentResolved(comment.id, message = replyBody, agentName = "codex", runId = "run-1")
        val editor = createEditor()
        showReviewCommentInlays(project, editor, ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT))
        return Triple(editor, editor.commentInlayComponents().single(), comment)
    }

    private fun summaryOf(panel: JPanel): SimpleColoredComponent =
        findComponents(panel).filterIsInstance<SimpleColoredComponent>().single()

    private fun buttons(panel: JPanel): List<JButton> = findComponents(panel).filterIsInstance<JButton>()

    private fun readOnlyTexts(panel: JPanel): List<String> =
        findComponents(panel).filterIsInstance<JTextArea>().filter { !it.isEditable }.map { it.text }

    /**
     * Clicks the real "⋯" comment-actions menu button and returns the action group it captured for
     * itself just before attempting `JPopupMenu.show()`. That show() call always throws headlessly here
     * (the inlay's button is never realized on an actual screen in this test environment) — catching that
     * expected failure, rather than building the group directly, is what proves the production button
     * really reaches buildCommentActionsGroup() instead of some other/empty group. The exact exception
     * message is a platform implementation detail and is intentionally not asserted here; a genuinely
     * missing group still fails loudly via commentActionsGroupForTest()'s IllegalStateException below.
     */
    private fun openCommentActionsGroup(panel: JPanel): DefaultActionGroup {
        val menuButton = buttons(panel).single { it.toolTipText == "Comment actions" }
        try {
            menuButton.doClick()
            error("expected JPopupMenu.show() to throw IllegalArgumentException in this headless test")
        } catch (e: IllegalArgumentException) {
            // Expected: JPopupMenu.show() cannot realize a component in this headless environment.
        }
        return (panel as ExistingCommentPanelTestAccess).commentActionsGroupForTest()
    }

    /**
     * Wraps [real] in a dynamic proxy that counts calls to [Inlay.update] and forwards everything else
     * unchanged. Used to observe that production code genuinely invokes the platform's Inlay.update(),
     * since heightInPixels itself does not change from update() alone in this headless environment —
     * only the platform-internal parent container's doLayout() recomputes the cached inlay size (see
     * refreshLayoutCallsTheRealInlayUpdateNotJustItsOwnCounter for the sequencing evidence).
     */
    private fun recordingInlay(real: Inlay<*>, onUpdate: () -> Unit): Inlay<*> {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            if (method.name == "update" && (args == null || args.isEmpty())) {
                onUpdate()
            }
            try {
                if (args == null) method.invoke(real) else method.invoke(real, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(Inlay::class.java.classLoader, arrayOf(Inlay::class.java), handler) as Inlay<*>
    }

    private fun EditorEx.singleCommentInlay(): com.intellij.openapi.editor.Inlay<*> = inlayModel
        .getBlockElementsInRange(0, document.textLength, ComponentInlayRenderer::class.java)
        .single()

    @Test
    fun resolvedCommentRendersCollapsedWithElidedSummaryAndNoBody() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, _) = resolvedCommentInEditor("collapsed")
            try {
                assertThat(editor.commentInlayComponents()).hasSize(1)
                val summary = summaryOf(panel).toString()
                assertThat(summary).startsWith("First paragraph").endsWith("…")
                assertThat(summary).doesNotContain("FINAL-MARKER").doesNotContain("\n")
                assertThat(readOnlyTexts(panel)).noneMatch { it.contains("FINAL-MARKER") }
                assertThat(readOnlyTexts(panel)).noneMatch { it.contains("AuthHelper") }
                assertThat(buttons(panel).map { it.text }).doesNotContain("Reply", "Reopen")
                val expandButton = buttons(panel).single { it.toolTipText == "Expand resolved comment" }
                assertThat(expandButton.isFocusable).isTrue()
                assertThat(expandButton.accessibleContext.accessibleName).isEqualTo("Expand resolved comment")
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text })
                    .contains("Resolved", "Line 1")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun resolvedMultiLineCommentRendersLinesRangePillCollapsedAndExpanded() {
        ApplicationManager.getApplication().invokeAndWait {
            val review = seededReview("multi-line")
            val changedFile = sampleChangedFile("src/Foo.kt")
            ReviewStateService.getInstance(project).addReview(review)
            val manager = ReviewManagerService.getInstance(project)
            val comment = manager.addComment(
                review.id, changedFile, DiffSide.RIGHT, 1, "spans a few lines", endLineNumber = 3,
            ) ?: error("comment missing")
            manager.markCommentResolved(comment.id)
            val editor = createEditor()
            try {
                showReviewCommentInlays(project, editor, ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT))
                val panel = editor.commentInlayComponents().single()

                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).contains("Lines 1-3")

                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).contains("Lines 1-3")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun resolvedCommentSummaryAndTooltipRenderHtmlPrefixLiterally() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, _) = resolvedCommentInEditor("html", body = "<html><b>bold</b> text")
            try {
                val summary = summaryOf(panel)
                assertThat(summary.toString()).isEqualTo("<html><b>bold</b> text")
                // Swing renders a tooltip beginning with "<html>" as markup. We OWN that wrapper and put
                // only escaped user text inside it, so the user's "<html><b>" is displayed as literal text.
                assertThat(summary.toolTipText)
                    .startsWith("<html>")
                    .endsWith("</html>")
                    .contains("&lt;html&gt;&lt;b&gt;bold&lt;/b&gt; text")
                    .doesNotContain("<b>")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun fullBodyTooltipConvertsNewlinesToHtmlLineBreaks() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, _) = resolvedCommentInEditor("multiline-tooltip", body = "first line\nsecond line")
            try {
                val summary = summaryOf(panel)
                // The tooltip's whole purpose is showing a multi-line body; the single-line HTML-prefix
                // test above never exercises the \n -> <br> half of fullBodyTooltip.
                assertThat(summary.toolTipText)
                    .startsWith("<html>")
                    .endsWith("</html>")
                    .contains("first line<br>second line")
                    .doesNotContain("first line\nsecond line")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun expandingResolvedCommentRevealsBodyThreadAndReopenOnlyAndGrowsInlay() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, _) = resolvedCommentInEditor("expand")
            try {
                val testAccess = panel as ExistingCommentPanelTestAccess
                val refreshCountBeforeExpand = testAccess.refreshLayoutCallCountForTest()
                val collapsedHeight = editor.singleCommentInlay().heightInPixels
                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                // Prove toggleExpanded() actually called refreshLayout() — independent of the manual
                // doLayout() crutch below, which only recomputes the *inlay's* cached size, not whether
                // the panel asked for it.
                assertThat(testAccess.refreshLayoutCallCountForTest()).isEqualTo(refreshCountBeforeExpand + 1)
                // Headless editor lays out lazily: the platform's ComponentInlaysContainer (panel's
                // direct parent) only re-measures the inlay's height on its own doLayout(), which is
                // normally scheduled asynchronously via RepaintManager and never flushed in this test.
                // Invoke it directly before reading heightInPixels.
                panel.parent?.doLayout()

                assertThat(editor.commentInlayComponents()).hasSize(1)
                assertThat(readOnlyTexts(panel)).anyMatch { it.contains("FINAL-MARKER") }
                assertThat(readOnlyTexts(panel)).anyMatch { it.contains("AuthHelper") }
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).contains("Line 1")
                val buttonTexts = buttons(panel).map { it.text }
                assertThat(buttonTexts).contains("Reopen")
                assertThat(buttonTexts).doesNotContain("Reply", "Post reply", "Save", "Cancel")
                assertThat(buttons(panel).map { it.toolTipText }).doesNotContain("Comment actions")
                val collapseButton = buttons(panel).single { it.toolTipText == "Collapse resolved comment" }
                assertThat(collapseButton.isFocusable).isTrue()
                assertThat(collapseButton.accessibleContext.accessibleName).isEqualTo("Collapse resolved comment")
                assertThat(findComponents(panel).filterIsInstance<SimpleColoredComponent>()).isEmpty()

                val expandedHeight = editor.singleCommentInlay().heightInPixels
                assertThat(expandedHeight).isGreaterThan(collapsedHeight)

                val refreshCountBeforeCollapse = testAccess.refreshLayoutCallCountForTest()
                buttons(panel).single { it.toolTipText == "Collapse resolved comment" }.doClick()
                assertThat(testAccess.refreshLayoutCallCountForTest()).isEqualTo(refreshCountBeforeCollapse + 1)
                panel.parent?.doLayout()
                assertThat(editor.singleCommentInlay().heightInPixels).isEqualTo(collapsedHeight)
                assertThat(readOnlyTexts(panel)).noneMatch { it.contains("FINAL-MARKER") }
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun refreshLayoutCallsTheRealInlayUpdateNotJustItsOwnCounter() {
        // The refreshLayoutCallCount counter increments as refreshLayout()'s first statement, so it
        // stays green even if revalidate()/repaint()/inlayRef?.update() are deleted entirely. Sequencing
        // check (see probe evidence in the fix report): calling Inlay.update() alone, without the
        // platform-internal parent container's doLayout() having already recomputed the renderer's
        // cached size, does not change heightInPixels in this headless environment — so heightInPixels
        // cannot distinguish "update() ran" from "update() was deleted" here. A recording wrapper around
        // the real platform Inlay is the honest way to observe that refreshLayout() truly calls update().
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, _) = resolvedCommentInEditor("update-observed")
            try {
                val testAccess = panel as ExistingCommentPanelTestAccess
                val realInlay = editor.singleCommentInlay()
                var updateCallCount = 0
                testAccess.setInlayRefForTest(recordingInlay(realInlay) { updateCallCount++ })

                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                assertThat(updateCallCount)
                    .describedAs("toggleExpanded()'s refreshLayout() must call the real Inlay.update()")
                    .isEqualTo(1)

                buttons(panel).single { it.toolTipText == "Collapse resolved comment" }.doClick()
                assertThat(updateCallCount)
                    .describedAs("collapsing must also call the real Inlay.update() again")
                    .isEqualTo(2)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun reopenButtonWithNoActualChangeDoesNotRerenderOrRefreshLayout() {
        // Reaches onStatusTransition(false) through the real Reopen button, not a test-only seam: a
        // concurrent external reopen (e.g. an MCP tool call) races ahead of the button's own click.
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, comment) = resolvedCommentInEditor("no-change")
            try {
                val testAccess = panel as ExistingCommentPanelTestAccess
                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                val reopenButton = buttons(panel).single { it.text == "Reopen" }
                val refreshCountBefore = testAccess.refreshLayoutCallCountForTest()

                assertThat(reopenComment(project, comment.id)).isTrue()
                assertThat(comment.status).isEqualTo(CommentStatus.OPEN)

                // The button's own reopenComment(...) call is now a no-op (status already OPEN), so
                // onStatusTransition's `if (!changed) return` guard means renderForCurrentStatus()/
                // refreshLayout() never ran for this click: the expanded card keeps its original Reopen
                // button instance and the refresh counter is untouched.
                reopenButton.doClick()
                assertThat(buttons(panel).single { it.text == "Reopen" }).isSameAs(reopenButton)
                assertThat(testAccess.refreshLayoutCallCountForTest()).isEqualTo(refreshCountBefore)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun expandStateSurvivesInlayRecreation() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, comment) = resolvedCommentInEditor("survive")
            try {
                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                assertThat(ReviewManagerService.getInstance(project).isResolvedCommentExpanded(comment.id)).isTrue()

                // Simulate the ReviewToolWindowPanel rebuild: dispose every inlay and recreate from the model.
                editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength, ComponentInlayRenderer::class.java)
                    .forEach { com.intellij.openapi.util.Disposer.dispose(it) }
                assertThat(editor.commentInlayComponents()).isEmpty()
                val changedFile = sampleChangedFile("src/Foo.kt")
                showReviewCommentInlays(project, editor, ReviewDiffRequestData(comment.reviewId, changedFile, DiffSide.RIGHT))

                val recreated = editor.commentInlayComponents().single()
                assertThat(readOnlyTexts(recreated)).anyMatch { it.contains("FINAL-MARKER") }
                assertThat(buttons(recreated).map { it.text }).contains("Reopen")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun resolvingOpenCardInStandaloneEditorRerendersAsCollapsedResolvedRow() {
        val review = seededReview("resolve-inplace")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val manager = ReviewManagerService.getInstance(project)
        val comment = manager.addComment(review.id, changedFile, DiffSide.RIGHT, 1, longBody) ?: error("comment missing")

        ApplicationManager.getApplication().invokeAndWait {
            val editor = createEditor()
            try {
                showReviewCommentInlays(project, editor, ReviewDiffRequestData(review.id, changedFile, DiffSide.RIGHT))
                val panel = editor.commentInlayComponents().single()
                assertThat(buttons(panel).map { it.text }).contains("Reply")

                val testAccess = panel as ExistingCommentPanelTestAccess
                val refreshCountBefore = testAccess.refreshLayoutCallCountForTest()
                // Drive the real `⋯` menu "Resolve comment" AnAction instead of reimplementing its body:
                // this is the only path that proves the action is actually wired to onStatusTransition().
                val resolveAction = openCommentActionsGroup(panel).getChildActionsOrStubs()
                    .single { it.templateText == "Resolve comment" }
                resolveAction.actionPerformed(TestActionEvent.createTestEvent(resolveAction))

                assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
                assertThat(editor.commentInlayComponents()).hasSize(1)
                assertThat(editor.singleCommentInlay().isValid).isTrue()
                assertThat(summaryOf(panel).toString()).endsWith("…")
                assertThat(buttons(panel).map { it.text }).doesNotContain("Reply")
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).contains("Resolved")
                assertThat(testAccess.refreshLayoutCallCountForTest()).isEqualTo(refreshCountBefore + 1)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun stalePanelDoesNotRerenderAfterItsInlayIsDisposed() {
        // Models the ReviewToolWindowPanel host: its synchronous listener disposes this inlay before
        // the button handler resumes. The stale panel must leave itself alone. Driven through the real
        // Reopen button rather than a test-only seam.
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, comment) = resolvedCommentInEditor("stale")
            try {
                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                val reopenButton = buttons(panel).single { it.text == "Reopen" }

                val inlay = editor.singleCommentInlay()
                com.intellij.openapi.util.Disposer.dispose(inlay)
                assertThat(inlay.isValid).isFalse()

                reopenButton.doClick()

                // reopenComment(...) inside the click handler genuinely changed the model...
                assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
                // ...but the stale panel's inlayRef?.isValid guard means it never re-rendered: the expanded
                // resolved presentation (with its original Reopen button instance) is untouched, not
                // swapped for an open card.
                assertThat(buttons(panel).single { it.text == "Reopen" }).isSameAs(reopenButton)
                assertThat(buttons(panel).map { it.text }).doesNotContain("Reply")
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).doesNotContain("Open")
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    @Test
    fun reopeningExpandedCardInStandaloneEditorRerendersAsOpenCard() {
        ApplicationManager.getApplication().invokeAndWait {
            val (editor, panel, comment) = resolvedCommentInEditor("reopen-inplace")
            try {
                buttons(panel).single { it.toolTipText == "Expand resolved comment" }.doClick()
                buttons(panel).single { it.text == "Reopen" }.doClick()

                assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
                assertThat(editor.commentInlayComponents()).hasSize(1)
                assertThat(editor.singleCommentInlay().isValid).isTrue()
                val buttonTexts = buttons(panel).map { it.text }
                assertThat(buttonTexts).contains("Reply").doesNotContain("Reopen")
                assertThat(buttons(panel).map { it.toolTipText }).contains("Comment actions")
                assertThat(findComponents(panel).filterIsInstance<JLabel>().map { it.text }).contains("Open")
                assertThat(readOnlyTexts(panel)).anyMatch { it.contains("AuthHelper") } // history kept
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }
    }

    private fun isEffectivelyVisible(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    private fun createEditor(): EditorEx = EditorFactory.getInstance()
        .createViewer(EditorFactory.getInstance().createDocument("one\ntwo\nthree\n"), project) as EditorEx

    private fun EditorEx.commentInlayComponents(): List<JPanel> = inlayModel
        .getBlockElementsInRange(0, document.textLength, ComponentInlayRenderer::class.java)
        .mapNotNull { it.renderer.component as? JPanel }

    private fun seededReview(suffix: String): Review = Review(
        id = "review-$suffix",
        title = "Review $suffix",
        target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "abc123"),
        repositoryRoot = project.basePath ?: "",
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:20:00+03:00",
    )

    private fun sampleChangedFile(path: String): ChangedFile = ChangedFile(
        filePath = path,
        status = ChangedFileStatus.MODIFIED,
        beforeContent = ReviewContent("one\ntwo\nthree\n", "HEAD", path),
        afterContent = ReviewContent("one\ntwo\nthree\n", "WORKTREE", path),
    )
}
