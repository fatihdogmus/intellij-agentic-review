package dev.fatihdogmus.agenticreview.export

import dev.fatihdogmus.agenticreview.diff.DiffContextExtractor
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReviewUnitTest {
    @Test
    fun promptBuilderIncludesDefaultsAndCommentPayload() {
        val exported = AgentPromptBuilder().build(sampleReview())

        assertThat(exported)
            .contains("### Comment 1")
            .contains("- Lines: 47")
            .contains("- Comment:")
    }

    @Test
    fun promptBuilderIncludesEscapedTextAndMetadata() {
        val review = sampleReview().copy(
            comments = mutableListOf(
                sampleReview().comments.first().copy(body = "Use <safe> & clear")
            )
        )

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("Use <safe> & clear")
            .contains("- Repository Root: `/tmp/repo`")
    }

    @Test
    fun promptBuilderIncludesOnlyOpenComments() {
        val review = sampleReview().copy(
            comments = mutableListOf(
                sampleReview().comments.first(),
                sampleReview().comments.first().copy(
                    id = "comment-2",
                    body = "already resolved",
                    status = CommentStatus.RESOLVED,
                ),
                sampleReview().comments.first().copy(
                    id = "comment-3",
                    body = "already resolved too",
                    status = CommentStatus.RESOLVED,
                ),
            ),
        )

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("- Open Comments: 1")
            .contains("Avoid !! here.")
            .doesNotContain("already resolved")
            .doesNotContain("already resolved too")
    }

    @Test
    fun promptBuilderShowsNoOpenCommentsAndOmitsAbsentMetadata() {
        val review = sampleReview().copy(
            target = ReviewTarget(type = ReviewTargetType.UNCOMMITTED),
            comments = mutableListOf(
                sampleReview().comments.first().copy(status = CommentStatus.RESOLVED),
            ),
        )

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("- Open Comments: 0")
            .contains("No open comments.")
            .doesNotContain("- Commit Hash:")
            .doesNotContain("- Parent Hash:")
            .doesNotContain("- Subject:")
    }

    @Test
    fun promptBuilderSortsByFileThenOldLineFallbackThenCreatedAt() {
        val base = sampleReview().comments.first()
        val review = sampleReview().copy(
            comments = mutableListOf(
                base.copy(
                    id = "c3",
                    filePath = "src/Zed.kt",
                    anchor = CommentAnchor(oldLine = 5),
                    body = "zed",
                    createdAt = "2026-05-07T14:22:00+03:00"
                ),
                base.copy(
                    id = "c2",
                    filePath = "src/Foo.kt",
                    anchor = CommentAnchor(oldLine = 3),
                    body = "foo-old",
                    createdAt = "2026-05-07T14:21:00+03:00"
                ),
                base.copy(
                    id = "c1",
                    filePath = "src/Foo.kt",
                    anchor = CommentAnchor(oldLine = 3),
                    body = "foo-earlier",
                    createdAt = "2026-05-07T14:20:00+03:00"
                ),
            ),
        )

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported.indexOf("foo-earlier")).isLessThan(exported.indexOf("foo-old"))
        assertThat(exported.indexOf("foo-old")).isLessThan(exported.indexOf("zed"))
        assertThat(exported).contains("- Lines: 3")
    }

    @Test
    fun diffContextExtractorBuildsMultiLineAnchorWithoutSelectedLinesInAfterContext() {
        val anchor = DiffContextExtractor().buildAnchor(
            changedFile = ChangedFile(
                filePath = "src/Foo.kt",
                status = ChangedFileStatus.MODIFIED,
                beforeContent = null,
                afterContent = ReviewContent(
                    text = "one\ntwo\nthree\nfour\nfive",
                    revisionTitle = "after",
                    filePath = "src/Foo.kt",
                ),
            ),
            side = DiffSide.RIGHT,
            lineNumber = 2,
            commitHash = "abc123",
            endLineNumber = 4,
        )

        assertThat(anchor.newLine).isEqualTo(2)
        assertThat(anchor.endNewLine).isEqualTo(4)
        assertThat(anchor.selectedText).isEqualTo("two\nthree\nfour")
        assertThat(anchor.beforeContext).containsExactly("one")
        assertThat(anchor.afterContext).containsExactly("five")
    }

    @Test
    fun diffContextExtractorBuildsLeftSideAnchorAtFileBoundary() {
        val anchor = DiffContextExtractor().buildAnchor(
            changedFile = ChangedFile(
                filePath = "src/Foo.kt",
                status = ChangedFileStatus.MODIFIED,
                beforeContent = ReviewContent(
                    text = "one\ntwo\nthree",
                    revisionTitle = "before",
                    filePath = "src/Foo.kt",
                ),
                afterContent = null,
            ),
            side = DiffSide.LEFT,
            lineNumber = 1,
            commitHash = "abc123",
        )

        assertThat(anchor.oldLine).isEqualTo(1)
        assertThat(anchor.newLine).isNull()
        assertThat(anchor.selectedText).isEqualTo("one")
        assertThat(anchor.beforeContext).isEmpty()
        assertThat(anchor.afterContext).containsExactly("two", "three")
    }

    @Test
    fun diffContextExtractorHandlesLastLineAndEmptyContent() {
        val extractor = DiffContextExtractor()
        val lastLine = extractor.buildAnchor(
            changedFile = ChangedFile(
                filePath = "src/Foo.kt",
                status = ChangedFileStatus.MODIFIED,
                beforeContent = null,
                afterContent = ReviewContent("one\ntwo", "after", "src/Foo.kt"),
            ),
            side = DiffSide.RIGHT,
            lineNumber = 2,
            commitHash = null,
        )
        val empty = extractor.buildAnchor(
            changedFile = ChangedFile(
                filePath = "src/Empty.kt",
                status = ChangedFileStatus.ADDED,
                beforeContent = null,
                afterContent = null,
            ),
            side = DiffSide.RIGHT,
            lineNumber = 1,
            commitHash = null,
        )

        assertThat(lastLine.selectedText).isEqualTo("two")
        assertThat(lastLine.beforeContext).containsExactly("one")
        assertThat(lastLine.afterContext).isEmpty()
        assertThat(empty.selectedText).isNull()
        assertThat(empty.beforeContext).isEmpty()
        assertThat(empty.afterContext).isEmpty()
    }

    @Test
    fun promptBuilderIncludesReplyThread() {
        val comment = sampleReview().comments.first().apply {
            replies = mutableListOf(
                CommentReply(
                    id = "reply-1",
                    commentId = id,
                    author = "codex",
                    authorKind = ReplyAuthorKind.AGENT,
                    body = "which call site did you mean?",
                    createdAt = "2026-09-01T09:00:00+03:00",
                ),
                CommentReply(
                    id = "reply-2",
                    commentId = id,
                    author = "you",
                    body = "the second one",
                    createdAt = "2026-09-01T09:05:00+03:00",
                ),
            )
        }
        val review = sampleReview().copy(comments = mutableListOf(comment))

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("- Thread:")
            .contains("**codex**")
            .contains("which call site did you mean?")
            .contains("**you**")
            .contains("the second one")
    }

    @Test
    fun promptBuilderRendersLegacyAgentMetadataThreadWhenNoStoredReplies() {
        val comment = sampleReview().comments.first().apply {
            agentMetadata = AgentMetadata(
                addressedBy = "codex",
                addressedAt = "2026-09-01T09:00:00+03:00",
                message = "handled previously",
            )
        }
        val review = sampleReview().copy(comments = mutableListOf(comment))

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("- Thread:")
            .contains("**codex**")
            .contains("handled previously")
    }

    @Test
    fun promptBuilderOmitsThreadSectionWhenNoReplies() {
        assertThat(AgentPromptBuilder().build(sampleReview())).doesNotContain("- Thread:")
    }

    @Test
    fun promptBuilderIndentsMultilineReplyBodies() {
        val comment = sampleReview().comments.first().apply {
            replies = mutableListOf(
                CommentReply(
                    id = "reply-1",
                    commentId = id,
                    author = "codex",
                    body = "first line\nsecond line",
                    createdAt = "2026-09-01T09:00:00+03:00",
                ),
            )
        }
        val review = sampleReview().copy(comments = mutableListOf(comment))

        val exported = AgentPromptBuilder().build(review)

        assertThat(exported)
            .contains("  - **codex** (2026-09-01T09:00:00+03:00): first line")
            .contains("\n    second line")
    }

    private fun sampleReview(): Review = Review(
        id = "review-unit-1",
        title = "Review abc123",
        target = ReviewTarget(
            type = ReviewTargetType.COMMIT,
            commitHash = "abc123def456",
            parentHash = "def456abc123",
            subject = "Fix user lookup",
        ),
        repositoryRoot = "/tmp/repo",
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:31:00+03:00",
        status = ReviewStatus.OPEN,
        comments = mutableListOf(
            ReviewComment(
                id = "comment-1",
                reviewId = "review-unit-1",
                filePath = "src/main/kotlin/Foo.kt",
                anchor = CommentAnchor(
                    side = DiffSide.RIGHT,
                    newLine = 47,
                    selectedText = "repo.find(id)!!",
                    beforeContext = listOf("fun findUser(id: UserId): User {"),
                    afterContext = listOf("}"),
                    commitHash = "abc123def456",
                ),
                body = "Avoid !! here.",
                status = CommentStatus.OPEN,
                createdAt = "2026-05-07T14:20:00+03:00",
                updatedAt = "2026-05-07T14:20:00+03:00",
            ),
        ),
        seenFiles = mutableListOf(),
    )
}
