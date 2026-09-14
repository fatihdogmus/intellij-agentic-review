@file:Suppress("unused")

package dev.fatihdogmus.agenticreview.mcp

import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.noSuitableProjectError
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.snapshot.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

class ReviewMcpToolset : McpToolset {
    private val json = Json { prettyPrint = true }

    @McpTool(name = "review_list_reviews")
    @McpDescription("List known Agentic Reviews for current project.")
    suspend fun reviewListReviews(
        @McpDescription("Optional review status filter. Example: OPEN")
        status: ReviewStatus? = null,
    ): String {
        val manager = manager()
        return json.encodeToString(ReviewListResult(manager.listReviews(status).map(::toReviewSummary)))
    }

    @McpTool(name = "review_get_review")
    @McpDescription("Get one review and optionally include comments.")
    suspend fun reviewGetReview(
        @McpDescription("Exact review id. Wins over selector when both are provided")
        reviewId: String? = null,
        @McpDescription("Review selector: current, latest, latest-open, uncommitted, or commit:<hash>")
        selector: String? = "current",
        @McpDescription("Include comments attached to review")
        includeComments: Boolean = true,
        @McpDescription("Include comments with RESOLVED status when includeComments is true")
        includeResolved: Boolean = false,
    ): String {
        val review = resolveReview(reviewId, selector)
        val comments = if (!includeComments) {
            emptyList()
        } else {
            review.comments
                .asSequence()
                .filter { includeResolved || it.status != CommentStatus.RESOLVED }
                .sortedWith(
                    compareBy(
                        { it.filePath },
                        { it.anchor.newLine ?: it.anchor.oldLine ?: Int.MAX_VALUE },
                        { it.createdAt })
                )
                .map(::toCommentSummary)
                .toList()
        }
        return json.encodeToString(ReviewResult(toReviewDetails(review, comments)))
    }

    @McpTool(name = "review_list_unresolved_comments")
    @McpDescription("List OPEN comments an agent should work on for one review.")
    suspend fun reviewListUnresolvedComments(
        @McpDescription("Exact review id. Wins over selector when both are provided")
        reviewId: String? = null,
        @McpDescription("Review selector: current, latest, latest-open, uncommitted, or commit:<hash>")
        selector: String? = "current",
    ): String {
        val review = resolveReview(reviewId, selector)
        val comments = review.comments
            .asSequence()
            .filter { it.status == CommentStatus.OPEN }
            .sortedWith(
                compareBy(
                    { it.filePath },
                    { it.anchor.newLine ?: it.anchor.oldLine ?: Int.MAX_VALUE },
                    { it.createdAt })
            )
            .map(::toCommentSummary)
            .toList()
        return json.encodeToString(CommentListResult(comments))
    }

    @McpTool(name = "review_get_comment_context")
    @McpDescription("Get full context for one review comment, including anchor and review metadata.")
    suspend fun reviewGetCommentContext(
        @McpDescription("Comment id")
        commentId: String,
    ): String {
        val manager = manager()
        val (review, comment) = manager.findCommentWithReview(commentId)
            ?: mcpFail("Comment not found: $commentId")
        return json.encodeToString(
            CommentContextResult(
                comment = toCommentSummary(comment),
                anchor = toCommentAnchorPayload(comment.anchor),
                review = toReviewReference(review),
            ),
        )
    }

    @McpTool(name = "review_mark_comment_resolved")
    @McpDescription("Resolve one comment after implementing requested code change.")
    suspend fun reviewMarkCommentResolved(
        @McpDescription("Comment id")
        commentId: String,
        @McpDescription("Optional implementation note from agent")
        message: String? = null,
        @McpDescription("Agent name. Defaults to MCP client name when available")
        agentName: String? = null,
        @McpDescription("Optional agent run id")
        runId: String? = null,
    ): String {
        val manager = manager()
        manager.findCommentWithReview(commentId) ?: mcpFail("Comment not found: $commentId")
        manager.markCommentResolved(
            commentId = commentId,
            message = message,
            agentName = agentName?.takeIf { it.isNotBlank() } ?: defaultAgentName(),
            runId = runId,
        )
        return json.encodeToString(
            MutationResult(
                ok = true,
                commentId = commentId,
                newStatus = CommentStatus.RESOLVED.name
            )
        )
    }

    @McpTool(name = "review_reply_to_comment")
    @McpDescription("Reply to a review comment without changing its status. Use to ask a clarifying question or push back before resolving. The returned replyCount counts stored replies only, not the projected replies array returned by other read tools.")
    suspend fun reviewReplyToComment(
        @McpDescription("Comment id")
        commentId: String,
        @McpDescription("Reply text")
        body: String,
        @McpDescription("Agent name. Defaults to MCP client name when available")
        agentName: String? = null,
        @McpDescription("Optional agent run id")
        runId: String? = null,
    ): String {
        if (body.isBlank()) mcpFail("Reply body must not be blank")
        val manager = manager()
        val (_, comment) = manager.findCommentWithReview(commentId)
            ?: mcpFail("Comment not found: $commentId")
        val reply = manager.addReply(
            commentId = commentId,
            body = body,
            author = agentName?.takeIf { it.isNotBlank() } ?: defaultAgentName(),
            authorKind = ReplyAuthorKind.AGENT,
            kind = ReplyKind.COMMENT,
            runId = runId,
        ) ?: mcpFail("Failed to add reply to comment: $commentId")
        return json.encodeToString(
            ReplyMutationResult(
                ok = true,
                commentId = commentId,
                replyId = reply.id,
                replyCount = comment.replies.size,
            ),
        )
    }

    @McpTool(name = "review_export")
    @McpDescription("Export one review as JSON or Markdown for agent consumption.")
    suspend fun reviewExport(
        @McpDescription("Exact review id. Wins over selector when both are provided")
        reviewId: String? = null,
        @McpDescription("Review selector: current, latest, latest-open, uncommitted, or commit:<hash>")
        selector: String? = "current",
        @McpDescription("Export format: json or markdown")
        format: String = "markdown",
    ): String {
        val manager = manager()
        val review = resolveReview(reviewId, selector)
        val normalizedFormat = format.lowercase()
        val content = when (normalizedFormat) {
            "json" -> json.encodeToString(review)
            "markdown" -> manager.buildAgentPrompt(review.id) ?: mcpFail("Review not found: ${review.id}")
            else -> mcpFail("Unsupported export format '$format'. Supported: json, markdown")
        }
        return json.encodeToString(ExportResult(format = normalizedFormat, content = content))
    }

    @McpTool(name = "review_turn_snapshot_begin")
    @McpDescription("Start recording an agent turn snapshot. Call before an agent begins editing files.")
    suspend fun reviewTurnSnapshotBegin(
        @McpDescription("OpenCode session id")
        sessionId: String,
        @McpDescription("OpenCode step/turn id")
        stepId: String,
        @McpDescription("Absolute path to the project root directory")
        projectPath: String,
        @McpDescription("Agent name (e.g. primary, codex)")
        agent: String? = null,
        @McpDescription("Model identifier")
        model: String? = null,
    ): String {
        val service = TurnSnapshotService.getInstance(currentProject())
        val turn = service.beginTurn(
            sessionId = sessionId,
            stepId = stepId,
            projectPath = projectPath,
            agent = agent,
            model = model,
        )
        return json.encodeToString(TurnSnapshotResult(ok = true, turnId = turn.id))
    }

    @McpTool(name = "review_turn_snapshot_end")
    @McpDescription("Finish recording an agent turn snapshot. Call after an agent finishes editing.")
    suspend fun reviewTurnSnapshotEnd(
        @McpDescription("OpenCode session id")
        sessionId: String,
        @McpDescription("OpenCode step/turn id")
        stepId: String,
        @McpDescription("Turn status: completed, failed, or overlapped")
        status: String = "completed",
        @McpDescription("JSON array of file paths changed during the turn")
        changedPathsJson: String? = null,
        @McpDescription("JSON array of tool call metadata objects")
        toolCallsJson: String? = null,
    ): String {
        val service = TurnSnapshotService.getInstance(currentProject())
        val changedPaths = changedPathsJson?.let { parseStringList(it) }
        val toolCalls = toolCallsJson?.let { parseToolCalls(it) }
        val turn = service.endTurn(
            sessionId = sessionId,
            stepId = stepId,
            status = status,
            changedPaths = changedPaths,
            toolCalls = toolCalls,
        )
        if (turn == null) {
            return json.encodeToString(
                TurnSnapshotResult(
                    ok = false,
                    turnId = "",
                    error = "No active turn found for session $sessionId"
                )
            )
        }
        return json.encodeToString(TurnSnapshotResult(ok = true, turnId = turn.id))
    }

    @McpTool(name = "review_list_turn_snapshots")
    @McpDescription("List completed turn snapshots for the current project.")
    suspend fun reviewListTurnSnapshots(): String {
        val service = TurnSnapshotService.getInstance(currentProject())
        val turns = service.getCompletedTurns().map { turn ->
            TurnSnapshotSummary(
                id = turn.id,
                sessionId = turn.sessionId,
                stepId = turn.stepId,
                agent = turn.agent,
                model = turn.model,
                startedAt = turn.startedAt,
                endedAt = turn.endedAt,
                status = turn.status,
                changedFileCount = turn.changedPaths.size,
            )
        }
        return json.encodeToString(TurnSnapshotListResult(turns))
    }

    private fun parseStringList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        return json.decodeFromString<List<String>>(trimmed)
    }

    private fun parseToolCalls(raw: String): List<TurnToolCall> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        return json.decodeFromString<List<TurnToolCall>>(trimmed)
    }

    private suspend fun manager(): ReviewManagerService = ReviewManagerService.getInstance(currentProject())

    private suspend fun currentProject(): Project = currentCoroutineContext().requireProject()

    private suspend fun resolveReview(reviewId: String?, selector: String?): Review {
        val manager = manager()
        reviewId?.takeIf { it.isNotBlank() }?.let {
            return manager.findReview(it) ?: mcpFail("Review not found: $it")
        }

        return when (val resolvedSelector = selector?.takeIf { it.isNotBlank() } ?: "current") {
            "current" -> manager.getCurrentReview() ?: mcpFail("No current review selected")
            "latest" -> manager.listReviews().firstOrNull() ?: mcpFail("No reviews found")
            "latest-open" -> manager.listReviews(ReviewStatus.OPEN).firstOrNull() ?: mcpFail("No open reviews found")
            "uncommitted" -> manager.listReviews()
                .filter { it.target.type == ReviewTargetType.UNCOMMITTED }
                .firstOrNull() ?: mcpFail("No uncommitted review found")

            else -> resolveSelectorReview(manager, resolvedSelector)
        }
    }

    private fun resolveSelectorReview(manager: ReviewManagerService, selector: String): Review {
        if (!selector.startsWith("commit:")) {
            mcpFail("Unsupported review selector '$selector'. Supported: current, latest, latest-open, uncommitted, commit:<hash>")
        }
        val commitHash = selector.removePrefix("commit:").trim()
        if (commitHash.isBlank()) {
            mcpFail("Commit selector must include hash, example: commit:abc123")
        }
        val matches = manager.listReviews().filter { review ->
            review.target.type == ReviewTargetType.COMMIT &&
                review.target.commitHash?.startsWith(commitHash, ignoreCase = true) == true
        }
        return when (matches.size) {
            0 -> mcpFail("No review found for commit selector '$selector'")
            1 -> matches.single()
            else -> mcpFail(
                "Multiple reviews match selector '$selector': ${matches.joinToString { "${it.id} (${it.title})" }}. Use exact reviewId.",
            )
        }
    }

    private suspend fun defaultAgentName(): String =
        currentCoroutineContext()[McpCallAdditionalDataElement]?.additionalData?.clientInfo?.name ?: "agent"

    private fun CoroutineContext.requireProject(): Project {
        this[McpCallAdditionalDataElement]?.additionalData?.project?.let { return it }
        val openProjects = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        return when (openProjects.size) {
            0 -> mcpFail("No project opened")
            1 -> openProjects.single()
            else -> throw noSuitableProjectError("No exact project is specified while multiple projects are opened.")
        }
    }

    private fun toReviewSummary(review: Review): ReviewSummary {
        return ReviewSummary(
            id = review.id,
            title = review.title,
            type = review.target.type,
            status = review.status,
            openCommentCount = review.comments.count { it.status == CommentStatus.OPEN },
            resolvedCommentCount = review.comments.count { it.status == CommentStatus.RESOLVED },
            repositoryRoot = review.repositoryRoot,
            updatedAt = review.updatedAt,
        )
    }

    private fun toReviewDetails(review: Review, comments: List<CommentSummary>): ReviewDetails {
        return ReviewDetails(
            id = review.id,
            title = review.title,
            target = review.target,
            repositoryRoot = review.repositoryRoot,
            createdAt = review.createdAt,
            updatedAt = review.updatedAt,
            status = review.status,
            openCommentCount = review.comments.count { it.status == CommentStatus.OPEN },
            resolvedCommentCount = review.comments.count { it.status == CommentStatus.RESOLVED },
            comments = comments,
        )
    }

    private fun toReviewReference(review: Review): ReviewReference {
        return ReviewReference(
            id = review.id,
            title = review.title,
            target = review.target,
            repositoryRoot = review.repositoryRoot,
            status = review.status,
        )
    }

    private fun toCommentSummary(comment: ReviewComment): CommentSummary {
        return CommentSummary(
            id = comment.id,
            reviewId = comment.reviewId,
            filePath = comment.filePath,
            line = comment.anchor.newLine ?: comment.anchor.oldLine,
            endLine = comment.anchor.endNewLine ?: comment.anchor.endOldLine,
            body = comment.body,
            status = comment.status,
            selectedText = comment.anchor.selectedText,
            createdAt = comment.createdAt,
            updatedAt = comment.updatedAt,
            author = comment.author,
            agentMetadata = comment.agentMetadata,
            replies = comment.thread().map { reply ->
                CommentReplyPayload(
                    id = reply.id,
                    author = reply.author,
                    authorKind = reply.authorKind,
                    kind = reply.kind,
                    body = reply.body,
                    createdAt = reply.createdAt,
                    runId = reply.runId,
                )
            },
        )
    }

    private fun toCommentAnchorPayload(anchor: CommentAnchor): CommentAnchorPayload {
        return CommentAnchorPayload(
            oldLine = anchor.oldLine,
            newLine = anchor.newLine,
            endOldLine = anchor.endOldLine,
            endNewLine = anchor.endNewLine,
            hunkHeader = anchor.hunkHeader,
            selectedText = anchor.selectedText,
            beforeContext = anchor.beforeContext,
            afterContext = anchor.afterContext,
            commitHash = anchor.commitHash,
        )
    }
}

@Serializable
data class ReviewListResult(
    val reviews: List<ReviewSummary>,
)

@Serializable
data class ReviewSummary(
    val id: String,
    val title: String,
    val type: ReviewTargetType,
    val status: ReviewStatus,
    val openCommentCount: Int,
    val resolvedCommentCount: Int,
    val repositoryRoot: String,
    val updatedAt: String,
)

@Serializable
data class ReviewResult(
    val review: ReviewDetails,
)

@Serializable
data class ReviewDetails(
    val id: String,
    val title: String,
    val target: ReviewTarget,
    val repositoryRoot: String,
    val createdAt: String,
    val updatedAt: String,
    val status: ReviewStatus,
    val openCommentCount: Int,
    val resolvedCommentCount: Int,
    val comments: List<CommentSummary>,
)

@Serializable
data class CommentListResult(
    val comments: List<CommentSummary>,
)

@Serializable
data class CommentSummary(
    val id: String,
    val reviewId: String,
    val filePath: String,
    val line: Int?,
    val endLine: Int?,
    val body: String,
    val status: CommentStatus,
    val selectedText: String?,
    val createdAt: String,
    val updatedAt: String,
    val author: String?,
    val agentMetadata: AgentMetadata?,
    val replies: List<CommentReplyPayload> = emptyList(),
)

@Serializable
data class CommentContextResult(
    val comment: CommentSummary,
    val anchor: CommentAnchorPayload,
    val review: ReviewReference,
)

@Serializable
data class CommentAnchorPayload(
    val oldLine: Int?,
    val newLine: Int?,
    val endOldLine: Int?,
    val endNewLine: Int?,
    val hunkHeader: String?,
    val selectedText: String?,
    val beforeContext: List<String>,
    val afterContext: List<String>,
    val commitHash: String?,
)

@Serializable
data class ReviewReference(
    val id: String,
    val title: String,
    val target: ReviewTarget,
    val repositoryRoot: String,
    val status: ReviewStatus,
)

@Serializable
data class MutationResult(
    val ok: Boolean,
    val commentId: String? = null,
    val newStatus: String? = null,
    val error: String? = null,
)

@Serializable
data class ExportResult(
    val format: String,
    val content: String,
)

@Serializable
data class CommentReplyPayload(
    val id: String,
    val author: String,
    val authorKind: ReplyAuthorKind,
    val kind: ReplyKind,
    val body: String,
    val createdAt: String,
    val runId: String? = null,
)

@Serializable
data class ReplyMutationResult(
    val ok: Boolean,
    val commentId: String,
    val replyId: String,
    val replyCount: Int,
)
