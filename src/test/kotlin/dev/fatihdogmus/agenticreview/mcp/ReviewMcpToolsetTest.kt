package dev.fatihdogmus.agenticreview.mcp

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotListResult
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotResult
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@TestApplication
class ReviewMcpToolsetTest {
    private val project by projectFixture(
        openAfterCreation = true,
        openProjectTask = OpenProjectTask { createModule = false })
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun reviewListUnresolvedCommentsUsesCurrentReviewByDefault() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("current-review")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)

            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val resolved = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")
            manager.markCommentResolved(resolved.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 3, "rename variable")

            val result = json.decodeFromString<CommentListResult>(ReviewMcpToolset().reviewListUnresolvedComments())

            assertThat(result.comments).hasSize(1)
            assertThat(result.comments.single().body).isEqualTo("rename variable")
        }
    }

    @Test
    fun reviewGetReviewSupportsCommitSelector() {
        runBlocking {
            val review = seededReview(
                suffix = "commit-selector",
                target = ReviewTarget(
                    type = ReviewTargetType.COMMIT,
                    commitHash = "mcp987654321",
                    parentHash = "0000000",
                    subject = "Fix issue",
                ),
            )
            ReviewStateService.getInstance(project).addReview(review)

            val result = json.decodeFromString<ReviewResult>(
                ReviewMcpToolset().reviewGetReview(selector = "commit:mcp987", includeComments = false),
            )

            assertThat(result.review.id).isEqualTo(review.id)
            assertThat(result.review.target.type).isEqualTo(ReviewTargetType.COMMIT)
            assertThat(result.review.comments).isEmpty()
        }
    }

    @Test
    fun reviewMarkCommentResolvedStoresAgentMetadata() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("resolved")
            ReviewStateService.getInstance(project).addReview(review)

            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "avoid bang bang")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")

            val result = json.decodeFromString<MutationResult>(
                ReviewMcpToolset().reviewMarkCommentResolved(
                    commentId = comment.id,
                    message = "Replaced with explicit null branch",
                    agentName = "codex",
                    runId = "run-42",
                ),
            )

            val updated = manager.findReview(review.id)?.comments?.single() ?: error("updated comment missing")
            assertThat(result.ok).isTrue()
            assertThat(result.newStatus).isEqualTo("RESOLVED")
            assertThat(updated.status).isEqualTo(CommentStatus.RESOLVED)
            assertThat(updated.agentMetadata?.addressedBy).isEqualTo("codex")
            assertThat(updated.agentMetadata?.message).isNull()
            assertThat(updated.replies.single().body).isEqualTo("Replaced with explicit null branch")
            assertThat(updated.replies.single().kind).isEqualTo(ReplyKind.RESOLUTION)
            assertThat(updated.agentMetadata?.runId).isEqualTo("run-42")
        }
    }

    @Test
    fun reviewGetReviewReportsOpenAndResolvedCommentCounts() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("counts")
            ReviewStateService.getInstance(project).addReview(review)

            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "open comment")
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 3, "resolved comment")
            val resolved = manager.findReview(review.id)?.comments?.last() ?: error("comment missing")
            manager.markCommentResolved(resolved.id)

            val result = json.decodeFromString<ReviewResult>(
                ReviewMcpToolset().reviewGetReview(reviewId = review.id, includeComments = true, includeResolved = true),
            )

            assertThat(result.review.openCommentCount).isEqualTo(1)
            assertThat(result.review.resolvedCommentCount).isEqualTo(1)
            assertThat(result.review.comments).hasSize(2)
        }
    }

    @Test
    fun reviewGetReviewCanExcludeCommentsAndUseLatestOpenSelector() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview(
                suffix = "latest-open",
                target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "latest-open-1", parentHash = "0000000"),
            )
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "open")

            val result = json.decodeFromString<ReviewResult>(
                ReviewMcpToolset().reviewGetReview(selector = "latest-open", includeComments = false),
            )

            assertThat(result.review.id).isEqualTo(review.id)
            assertThat(result.review.comments).isEmpty()
            assertThat(result.review.openCommentCount).isEqualTo(1)
        }
    }

    @Test
    fun reviewGetReviewCanResolveUncommittedSelectorAndHideResolvedComments() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = manager.getCurrentReview() ?: error("current review missing")
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "open")
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 3, "resolved")
            val resolved = manager.findReview(review.id)!!.comments.last().id
            manager.markCommentResolved(resolved)

            val result = json.decodeFromString<ReviewResult>(
                ReviewMcpToolset().reviewGetReview(
                    selector = "uncommitted",
                    includeComments = true,
                    includeResolved = false
                ),
            )

            assertThat(result.review.target.type).isEqualTo(ReviewTargetType.UNCOMMITTED)
            assertThat(result.review.comments).singleElement().extracting("body").isEqualTo("open")
        }
    }

    @Test
    fun reviewExportSupportsJsonFormat() {
        runBlocking {
            val review = seededReview(
                suffix = "export-json",
                target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "json-1", parentHash = "0000000"),
            )
            ReviewStateService.getInstance(project).addReview(review)

            val result = json.decodeFromString<ExportResult>(
                ReviewMcpToolset().reviewExport(reviewId = review.id, format = "json"),
            )

            assertThat(result.format).isEqualTo("json")
            assertThat(result.content).contains("\"id\": \"${review.id}\"")
        }
    }

    @Test
    fun reviewTurnSnapshotBeginAndEndLifecycle() {
        runBlocking {
            val sessionId = "session-1"
            val stepId = "step-1"

            val beginResult = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotBegin(
                    sessionId = sessionId,
                    stepId = stepId,
                    projectPath = project.basePath!!,
                    agent = "primary",
                    model = "claude-4",
                ),
            )

            assertThat(beginResult.ok).isTrue()
            assertThat(beginResult.turnId).isNotBlank()

            val endResult = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotEnd(
                    sessionId = sessionId,
                    stepId = stepId,
                    status = "completed",
                ),
            )

            assertThat(endResult.ok).isTrue()
            assertThat(endResult.turnId).isEqualTo(beginResult.turnId)

            val listResult = json.decodeFromString<TurnSnapshotListResult>(
                ReviewMcpToolset().reviewListTurnSnapshots(),
            )

            assertThat(listResult.turns).hasSize(1)
            assertThat(listResult.turns.single().sessionId).isEqualTo(sessionId)
            assertThat(listResult.turns.single().status).isEqualTo("completed")
            assertThat(listResult.turns.single().agent).isEqualTo("primary")
        }
    }

    @Test
    fun reviewTurnSnapshotEndWithoutActiveTurnReturnsError() {
        runBlocking {
            val result = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotEnd(
                    sessionId = "nonexistent",
                    stepId = "step-1",
                ),
            )

            assertThat(result.ok).isFalse()
            assertThat(result.turnId).isBlank()
        }
    }

    @Test
    fun reviewTurnSnapshotBeginOverlapsExisting() {
        runBlocking {
            val session1 = "session-1"
            val session2 = "session-2"

            val begin1 = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotBegin(
                    sessionId = session1,
                    stepId = "step-1",
                    projectPath = project.basePath!!,
                ),
            )
            assertThat(begin1.ok).isTrue()

            val begin2 = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotBegin(
                    sessionId = session2,
                    stepId = "step-2",
                    projectPath = project.basePath!!,
                ),
            )
            assertThat(begin2.ok).isTrue()

            val listResult = json.decodeFromString<TurnSnapshotListResult>(
                ReviewMcpToolset().reviewListTurnSnapshots(),
            )

            assertThat(listResult.turns).hasSize(1)
            assertThat(listResult.turns.single().sessionId).isEqualTo(session1)
            assertThat(listResult.turns.single().status).isEqualTo("overlapped")
        }
    }

    @Test
    fun reviewTurnSnapshotBeginSucceedsWithoutOptionalFields() {
        runBlocking {
            val result = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotBegin(
                    sessionId = "session-min",
                    stepId = "step-min",
                    projectPath = project.basePath!!,
                ),
            )

            assertThat(result.ok).isTrue()

            val listResult = json.decodeFromString<TurnSnapshotListResult>(
                ReviewMcpToolset().reviewListTurnSnapshots(),
            )

            assertThat(listResult.turns).hasSize(0)

            ReviewMcpToolset().reviewTurnSnapshotEnd(
                sessionId = "session-min",
                stepId = "step-min",
            )
        }
    }

    @Test
    fun reviewTurnSnapshotEndWithChangedPaths() {
        runBlocking {
            val sessionId = "session-paths"

            ReviewMcpToolset().reviewTurnSnapshotBegin(
                sessionId = sessionId,
                stepId = "step-paths",
                projectPath = project.basePath!!,
            )

            val changedPaths = """["src/main/Foo.kt", "src/test/Bar.kt"]"""
            val toolCalls = """[{"callId":"c1","tool":"edit","changedPaths":["src/main/Foo.kt"],"metadataJson":null}]"""

            val endResult = json.decodeFromString<TurnSnapshotResult>(
                ReviewMcpToolset().reviewTurnSnapshotEnd(
                    sessionId = sessionId,
                    stepId = "step-paths",
                    status = "completed",
                    changedPathsJson = changedPaths,
                    toolCallsJson = toolCalls,
                ),
            )

            assertThat(endResult.ok).isTrue()

            val listResult = json.decodeFromString<TurnSnapshotListResult>(
                ReviewMcpToolset().reviewListTurnSnapshots(),
            )

            assertThat(listResult.turns).hasSize(1)
            assertThat(listResult.turns.single().changedFileCount).isEqualTo(2)
        }
    }

    @Test
    fun reviewReplyToCommentAppendsWithoutResolving() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("reply-tool")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")

            val result = json.decodeFromString<ReplyMutationResult>(
                ReviewMcpToolset().reviewReplyToComment(comment.id, "which call site did you mean?"),
            )

            assertThat(result.ok).isTrue()
            assertThat(result.commentId).isEqualTo(comment.id)
            assertThat(result.replyId).isNotBlank()
            assertThat(result.replyCount).isEqualTo(1)
            assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
            val reply = comment.replies.single()
            assertThat(reply.body).isEqualTo("which call site did you mean?")
            assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
            assertThat(reply.kind).isEqualTo(ReplyKind.COMMENT)
        }
    }

    @Test
    fun reviewReplyToCommentAcceptsExplicitAgentNameAndRunId() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("reply-explicit")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")

            val result = json.decodeFromString<ReplyMutationResult>(
                ReviewMcpToolset().reviewReplyToComment(
                    commentId = comment.id,
                    body = "checked the call site",
                    agentName = "codex",
                    runId = "run-99",
                ),
            )

            assertThat(result.ok).isTrue()
            val reply = comment.replies.single()
            assertThat(reply.author).isEqualTo("codex")
            assertThat(reply.runId).isEqualTo("run-99")
        }
    }

    @Test
    fun commentSummaryCarriesReplies() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("summary-replies")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")
            manager.addReply(comment.id, "guarded at L42", author = "codex", authorKind = ReplyAuthorKind.AGENT)

            val result = json.decodeFromString<CommentListResult>(
                ReviewMcpToolset().reviewListUnresolvedComments(),
            )

            val replies = result.comments.single().replies
            assertThat(replies).hasSize(1)
            assertThat(replies.single().body).isEqualTo("guarded at L42")
            assertThat(replies.single().author).isEqualTo("codex")
        }
    }

    @Test
    fun commentSummaryExposesLegacyAgentMetadataAsReply() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("summary-legacy")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")
            comment.agentMetadata = AgentMetadata(
                addressedBy = "legacy-agent",
                addressedAt = "2026-09-01T09:00:00+03:00",
                message = "handled previously",
            )

            val result = json.decodeFromString<CommentListResult>(
                ReviewMcpToolset().reviewListUnresolvedComments(),
            )

            assertThat(result.comments.single().replies.single().body).isEqualTo("handled previously")
        }
    }

    @Test
    fun reviewReplyToCommentRejectsBlankBodyAndUnknownComment() {
        runBlocking {
            val manager = ReviewManagerService.getInstance(project)
            val review = seededReview("reply-failures")
            ReviewStateService.getInstance(project).addReview(review)
            manager.selectReview(review.id)
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 2, "fix null handling")
            val comment = manager.findReview(review.id)?.comments?.single() ?: error("comment missing")

            assertThatThrownBy { runBlocking { ReviewMcpToolset().reviewReplyToComment(comment.id, "   ") } }
                .hasMessageContaining("Reply body must not be blank")
            assertThatThrownBy { runBlocking { ReviewMcpToolset().reviewReplyToComment("nope", "hello") } }
                .hasMessageContaining("Comment not found: nope")
            assertThat(comment.replies).isEmpty()
        }
    }

    private fun seededReview(
        suffix: String,
        target: ReviewTarget = ReviewTarget(type = ReviewTargetType.UNCOMMITTED)
    ): Review = Review(
        id = "review-mcp-$suffix",
        title = "MCP review",
        target = target,
        repositoryRoot = "/tmp/repo",
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:20:00+03:00",
    )

    private fun sampleChangedFile(path: String): ChangedFile = ChangedFile(
        filePath = path,
        status = ChangedFileStatus.MODIFIED,
        beforeContent = ReviewContent(
            text = "zero\none\nold-three\nfour",
            revisionTitle = "before",
            filePath = path,
        ),
        afterContent = ReviewContent(
            text = "one\ntwo\nthree\nfour",
            revisionTitle = "after",
            filePath = path,
        ),
    )
}
