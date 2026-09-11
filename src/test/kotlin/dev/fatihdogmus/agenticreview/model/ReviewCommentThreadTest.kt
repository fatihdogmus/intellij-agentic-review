package dev.fatihdogmus.agenticreview.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReviewCommentThreadTest {

    @Test
    fun threadIsEmptyWhenNoRepliesAndNoAgentMetadata() {
        assertThat(comment().thread()).isEmpty()
    }

    @Test
    fun threadReturnsStoredReplies() {
        val target = comment().apply {
            replies = mutableListOf(reply("r1", "first"), reply("r2", "second"))
        }

        assertThat(target.thread().map { it.body }).containsExactly("first", "second")
    }

    @Test
    fun threadSynthesisesLegacyAgentMetadataMessage() {
        val target = comment().apply {
            agentMetadata = AgentMetadata(
                addressedBy = "codex",
                addressedAt = "2026-09-01T09:00:00+03:00",
                message = "done in a1b2c3",
                runId = "run-3",
            )
        }

        val synthesised = target.thread().single()

        assertThat(synthesised.body).isEqualTo("done in a1b2c3")
        assertThat(synthesised.author).isEqualTo("codex")
        assertThat(synthesised.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
        assertThat(synthesised.kind).isEqualTo(ReplyKind.RESOLUTION)
        assertThat(synthesised.createdAt).isEqualTo("2026-09-01T09:00:00+03:00")
        assertThat(synthesised.runId).isEqualTo("run-3")
    }

    @Test
    fun threadSynthesisesLegacyAgentMetadataMessageWithDefaultsWhenAddressedByAndAtAreNull() {
        val target = comment().apply {
            agentMetadata = AgentMetadata(message = "done without attribution")
        }

        val synthesised = target.thread().single()

        assertThat(synthesised.author).isEqualTo("agent")
        assertThat(synthesised.createdAt).isEqualTo(target.createdAt)
    }

    @Test
    fun storedRepliesWinOverLegacyAgentMetadata() {
        val target = comment().apply {
            replies = mutableListOf(reply("r1", "real reply"))
            agentMetadata = AgentMetadata(message = "legacy")
        }

        assertThat(target.thread().map { it.body }).containsExactly("real reply")
    }

    @Test
    fun blankLegacyMessageProducesNoReply() {
        val target = comment().apply {
            agentMetadata = AgentMetadata(addressedBy = "codex", message = "   ")
        }

        assertThat(target.thread()).isEmpty()
    }

    private fun comment(): ReviewComment = ReviewComment(
        id = "comment-1",
        reviewId = "review-1",
        filePath = "src/Foo.kt",
        body = "needs fix",
        createdAt = "2026-09-01T08:00:00+03:00",
        updatedAt = "2026-09-01T08:00:00+03:00",
    )

    private fun reply(id: String, body: String): CommentReply = CommentReply(
        id = id,
        commentId = "comment-1",
        author = "you",
        body = body,
        createdAt = "2026-09-01T08:30:00+03:00",
    )
}
