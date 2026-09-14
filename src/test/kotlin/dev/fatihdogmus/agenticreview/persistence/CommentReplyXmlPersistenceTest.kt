package dev.fatihdogmus.agenticreview.persistence

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import dev.fatihdogmus.agenticreview.model.CommentReply
import dev.fatihdogmus.agenticreview.model.ReplyAuthorKind
import dev.fatihdogmus.agenticreview.model.ReplyKind
import dev.fatihdogmus.agenticreview.model.ReviewComment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@TestApplication
class CommentReplyXmlPersistenceTest {

    @Test
    fun repliesSurviveIntellijXmlBeanSerialization() {
        val comment = sampleComment().apply {
            replies = mutableListOf(
                CommentReply(
                    id = "reply-1",
                    commentId = "comment-1",
                    author = "codex",
                    authorKind = ReplyAuthorKind.AGENT,
                    kind = ReplyKind.RESOLUTION,
                    body = "fixed",
                    createdAt = "2026-09-01T09:00:00+03:00",
                    runId = "run-1",
                ),
            )
        }

        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(comment),
            ReviewComment::class.java,
        )

        val reply = restored.replies.single()
        assertThat(reply.id).isEqualTo("reply-1")
        assertThat(reply.commentId).isEqualTo("comment-1")
        assertThat(reply.author).isEqualTo("codex")
        assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
        assertThat(reply.kind).isEqualTo(ReplyKind.RESOLUTION)
        assertThat(reply.body).isEqualTo("fixed")
        assertThat(reply.runId).isEqualTo("run-1")
    }

    @Test
    fun olderStateWithoutRepliesDeserializesToEmptyList() {
        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(sampleComment()),
            ReviewComment::class.java,
        )

        assertThat(restored.replies).isEmpty()
    }

    private fun sampleComment(): ReviewComment = ReviewComment(
        id = "comment-1",
        reviewId = "review-1",
        filePath = "src/Foo.kt",
        body = "needs fix",
        createdAt = "2026-09-01T08:00:00+03:00",
        updatedAt = "2026-09-01T08:00:00+03:00",
    )
}
