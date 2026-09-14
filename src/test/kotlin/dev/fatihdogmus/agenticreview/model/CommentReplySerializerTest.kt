package dev.fatihdogmus.agenticreview.model

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommentReplySerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replyRoundTripsThroughJson() {
        val reply = CommentReply(
            id = "reply-1",
            commentId = "comment-1",
            author = "codex",
            authorKind = ReplyAuthorKind.AGENT,
            kind = ReplyKind.RESOLUTION,
            body = "fixed in a1b2c3",
            createdAt = "2026-09-10T10:00:00+03:00",
            runId = "run-9",
        )

        val decoded = json.decodeFromString(
            CommentReply.serializer(),
            json.encodeToString(CommentReply.serializer(), reply),
        )

        assertThat(decoded).isEqualTo(reply)
    }

    @Test
    fun replyHasNoArgConstructorForXmlSerializer() {
        assertThat(CommentReply()).isNotNull
    }

    @Test
    fun unknownAuthorKindDecodesToHuman() {
        assertThat(json.decodeFromString(ReplyAuthorKind.serializer(), "\"ROBOT\""))
            .isEqualTo(ReplyAuthorKind.HUMAN)
    }

    @Test
    fun unknownReplyKindDecodesToComment() {
        assertThat(json.decodeFromString(ReplyKind.serializer(), "\"ESCALATION\""))
            .isEqualTo(ReplyKind.COMMENT)
    }

    @Test
    fun knownEnumValuesRoundTrip() {
        assertThat(json.encodeToString(ReplyAuthorKind.serializer(), ReplyAuthorKind.AGENT))
            .isEqualTo("\"AGENT\"")
        assertThat(json.encodeToString(ReplyKind.serializer(), ReplyKind.RESOLUTION))
            .isEqualTo("\"RESOLUTION\"")
    }
}
