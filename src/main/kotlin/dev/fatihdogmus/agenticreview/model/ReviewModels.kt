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
    var branchName: String? = null,
    var baseBranchName: String? = null,
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
