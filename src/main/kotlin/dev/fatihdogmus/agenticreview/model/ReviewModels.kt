package dev.fatihdogmus.agenticreview.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Review(
    var id: String = "",
    var title: String = "",
    var target: ReviewTarget = ReviewTarget(),
    var repositoryRoot: String = "",
    var createdAt: String = "",
    var updatedAt: String = "",
    var status: ReviewStatus = ReviewStatus.OPEN,
    var comments: MutableList<ReviewComment> = mutableListOf(),
    var seenFiles: MutableList<SeenFileState> = mutableListOf(),
)

@Serializable
data class SeenFileState(
    var key: String = "",
    var filePath: String = "",
    var seenAt: String = "",
)

@Serializable
data class ReviewTarget(
    var type: ReviewTargetType = ReviewTargetType.UNCOMMITTED,
    var baseRef: String? = null,
    var headRef: String? = null,
    var commitHash: String? = null,
    var parentHash: String? = null,
    var subject: String? = null,
    var changelistId: String? = null,
)

@Serializable
enum class ReviewTargetType {
    UNCOMMITTED,
    COMMIT,
    COMMIT_RANGE,
}

@Serializable
data class ReviewComment(
    var id: String = "",
    var reviewId: String = "",
    var filePath: String = "",
    var anchor: CommentAnchor = CommentAnchor(),
    var body: String = "",
    var status: CommentStatus = CommentStatus.OPEN,
    var createdAt: String = "",
    var updatedAt: String = "",
    var author: String? = null,
    var agentMetadata: AgentMetadata? = null,
    var replies: MutableList<CommentReply> = mutableListOf(),
)

@Serializable
data class CommentAnchor(
    var side: DiffSide = DiffSide.RIGHT,
    var oldLine: Int? = null,
    var newLine: Int? = null,
    var endOldLine: Int? = null,
    var endNewLine: Int? = null,
    var hunkHeader: String? = null,
    var selectedText: String? = null,
    var beforeContext: List<String> = emptyList(),
    var afterContext: List<String> = emptyList(),
    var commitHash: String? = null,
)

@Serializable
data class AgentMetadata(
    var addressedBy: String? = null,
    var addressedAt: String? = null,
    var message: String? = null,
    var runId: String? = null,
)

@Serializable
data class CommentReply(
    var id: String = "",
    var commentId: String = "",
    var author: String = "",
    var authorKind: ReplyAuthorKind = ReplyAuthorKind.HUMAN,
    var kind: ReplyKind = ReplyKind.COMMENT,
    var body: String = "",
    var createdAt: String = "",
    var runId: String? = null,
)

@Serializable(with = ReplyAuthorKindSerializer::class)
enum class ReplyAuthorKind {
    HUMAN,
    AGENT,
}

@Serializable(with = ReplyKindSerializer::class)
enum class ReplyKind {
    COMMENT,
    RESOLUTION,
}

object ReplyAuthorKindSerializer : KSerializer<ReplyAuthorKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReplyAuthorKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReplyAuthorKind) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ReplyAuthorKind =
        when (decoder.decodeString()) {
            ReplyAuthorKind.AGENT.name -> ReplyAuthorKind.AGENT
            else -> ReplyAuthorKind.HUMAN
        }
}

object ReplyKindSerializer : KSerializer<ReplyKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReplyKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReplyKind) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ReplyKind =
        when (decoder.decodeString()) {
            ReplyKind.RESOLUTION.name -> ReplyKind.RESOLUTION
            else -> ReplyKind.COMMENT
        }
}

@Serializable
enum class ReviewStatus {
    OPEN,
}

@Serializable(with = CommentStatusSerializer::class)
enum class CommentStatus {
    OPEN,
    RESOLVED,
}

object CommentStatusSerializer : KSerializer<CommentStatus> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CommentStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CommentStatus) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): CommentStatus {
        return when (decoder.decodeString()) {
            CommentStatus.OPEN.name -> CommentStatus.OPEN
            CommentStatus.RESOLVED.name -> CommentStatus.RESOLVED
            else -> CommentStatus.OPEN
        }
    }
}

@Serializable
enum class DiffSide {
    LEFT,
    RIGHT,
}

fun ReviewTarget.commitHashIfAny(): String? = commitHash

fun ReviewComment.thread(): List<CommentReply> {
    if (replies.isNotEmpty()) return replies.toList()
    val legacy = agentMetadata ?: return emptyList()
    val message = legacy.message?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(
        CommentReply(
            id = "legacy-$id",
            commentId = id,
            author = legacy.addressedBy ?: "agent",
            authorKind = ReplyAuthorKind.AGENT,
            kind = ReplyKind.RESOLUTION,
            body = message,
            createdAt = legacy.addressedAt ?: createdAt,
            runId = legacy.runId,
        ),
    )
}
