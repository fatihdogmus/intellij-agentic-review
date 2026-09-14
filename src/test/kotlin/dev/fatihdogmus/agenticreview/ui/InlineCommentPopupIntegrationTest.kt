package dev.fatihdogmus.agenticreview.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Component
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
    fun resolveCommentAndDismissOnlyDismissesAfterStateChanges() {
        val review = seededReview("resolve-dismiss")
        val changedFile = sampleChangedFile("src/Foo.kt")
        ReviewStateService.getInstance(project).addReview(review)
        val comment =
            ReviewManagerService.getInstance(project).addComment(review.id, changedFile, DiffSide.RIGHT, 1, "todo")!!
        var dismissCount = 0

        assertThat(resolveCommentAndDismiss(project, comment.id) { dismissCount++ }).isTrue()
        assertThat(dismissCount).isEqualTo(1)
        assertThat(ReviewManagerService.getInstance(project).commentsForFile(review.id, changedFile.filePath)).isEmpty()

        assertThat(resolveCommentAndDismiss(project, "missing") { dismissCount++ }).isFalse()
        assertThat(dismissCount).isEqualTo(1)
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
