package dev.fatihdogmus.agenticreview

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.SavedReviewArchive
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class ReviewImportValidationTest {
    private val project by projectFixture()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun loadReviewFromFileRejectsCommitOutsideCurrentBranch() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitArchive(),
            fileName = "not-reachable.json",
        )
        manager.isCommitReachableOnCurrentBranchSupplier = { false }

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("not reachable from current branch")
    }

    @Test
    fun loadReviewFromFileRejectsEndCommitOutsideCurrentBranch() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitRangeArchive(),
            fileName = "range-head-not-reachable.json",
        )
        manager.isCommitReachableOnCurrentBranchSupplier = { commit -> commit != "range-head-456" }

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("End commit is not reachable")
    }

    @Test
    fun loadReviewFromFileReturnsGenericMalformedErrorForInvalidJsonSyntax() {
        val manager = configuredManager()
        val file = writeRawJson("syntax-error.json", "{")

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).isEqualTo("The imported format is malformed")
    }

    @Test
    fun loadReviewFromFileReturnsGenericMalformedErrorForBlankTitle() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitArchive().copy(title = "   "),
            fileName = "blank-title.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).isEqualTo("The imported format is malformed")
    }

    @Test
    fun loadReviewFromFileReturnsGenericMalformedErrorForMissingCommitRangeBoundary() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitRangeArchive().copy(beginCommit = null),
            fileName = "missing-begin.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).isEqualTo("The imported format is malformed")
    }

    @Test
    fun loadReviewFromFileReturnsGenericMalformedErrorForUncommittedArchive() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitArchive().copy(targetType = ReviewTargetType.UNCOMMITTED),
            fileName = "uncommitted.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).isEqualTo("The imported format is malformed")
    }

    @Test
    fun loadReviewFromFileReturnsGenericMalformedErrorForBlankCommentBody() {
        val manager = configuredManager()
        val brokenComment = sampleComment().copy(body = "   ")
        val file = writeArchive(
            archive = validCommitArchive().copy(comments = listOf(brokenComment)),
            fileName = "blank-comment-body.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isFalse()
        assertThat(result.error).isEqualTo("The imported format is malformed")
    }

    @Test
    fun loadReviewFromFileImportsValidCommitArchive() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitArchive().copy(comments = emptyList()),
            fileName = "valid-commit.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isTrue()
        val review = manager.findReview(result.reviewId!!)
        assertThat(review).isNotNull
        assertThat(review!!.title).isEqualTo("Saved Commit Review")
        assertThat(review.target.type).isEqualTo(ReviewTargetType.COMMIT)
        assertThat(review.target.commitHash).isEqualTo("commit-head-123")
        assertThat(review.target.parentHash).isEqualTo("commit-base-456")
        assertThat(review.comments).isEmpty()
    }

    @Test
    fun loadReviewFromFileImportsValidCommitRangeArchiveAndRekeysComments() {
        val manager = configuredManager()
        val file = writeArchive(
            archive = validCommitRangeArchive(),
            fileName = "valid-range.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isTrue()
        val review = manager.findReview(result.reviewId!!)
        assertThat(review).isNotNull
        assertThat(review!!.title).isEqualTo("Saved Range Review")
        assertThat(review.target.type).isEqualTo(ReviewTargetType.COMMIT_RANGE)
        assertThat(review.target.baseRef).isEqualTo("range-base-123")
        assertThat(review.target.headRef).isEqualTo("range-head-456")
        assertThat(review.comments).hasSize(1)
        assertThat(review.comments.single().id).isNotEqualTo("comment-1")
        assertThat(review.comments.single().reviewId).isEqualTo(review.id)
    }

    @Test
    fun loadReviewFromFilePreservesBranchReviewIdentity() {
        val manager = configuredManager()
        val file = writeArchive(
            validCommitRangeArchive().copy(
                subject = "feature/test vs main",
                branchName = "feature/test",
                baseBranchName = "main",
            ),
            "valid-branch.json",
        )

        val result = manager.loadReviewFromFile(file)

        assertThat(result.ok).isTrue()
        val review = manager.findReview(result.reviewId!!)
        assertThat(review?.target?.branchName).isEqualTo("feature/test")
        assertThat(review?.target?.baseBranchName).isEqualTo("main")
        assertThat(manager.canRefreshBranchReview(review)).isTrue()
    }

    private fun configuredManager(): ReviewManagerService = ReviewManagerService.getInstance(project).also { manager ->
        val tempDir = Files.createTempDirectory("agentic-review-import-tests")
        manager.repositoryRootResolver = { tempDir.toString() }
        manager.isCommitReachableOnCurrentBranchSupplier = { true }
    }

    private fun writeArchive(archive: SavedReviewArchive, fileName: String): Path =
        writeRawJson(fileName, json.encodeToString(archive))

    private fun writeRawJson(fileName: String, content: String): Path {
        val tempDir = Files.createTempDirectory("agentic-review-import-json")
        val file = tempDir.resolve(fileName)
        Files.writeString(file, content)
        return file
    }

    private fun validCommitArchive(): SavedReviewArchive = SavedReviewArchive(
        originalReviewId = "saved-review-1",
        title = "Saved Commit Review",
        targetType = ReviewTargetType.COMMIT,
        beginCommit = "commit-base-456",
        endCommit = "commit-head-123",
        subject = "Fix issue",
        reviewStatus = ReviewStatus.OPEN,
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:31:00+03:00",
        comments = listOf(sampleComment()),
    )

    private fun validCommitRangeArchive(): SavedReviewArchive = SavedReviewArchive(
        originalReviewId = "saved-review-2",
        title = "Saved Range Review",
        targetType = ReviewTargetType.COMMIT_RANGE,
        beginCommit = "range-base-123",
        endCommit = "range-head-456",
        subject = "Feature branch",
        reviewStatus = ReviewStatus.OPEN,
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:31:00+03:00",
        comments = listOf(sampleComment()),
    )

    private fun sampleComment(): ReviewComment = ReviewComment(
        id = "comment-1",
        reviewId = "saved-review-1",
        filePath = "src/Foo.kt",
        anchor = CommentAnchor(
            side = DiffSide.RIGHT,
            newLine = 12,
            commitHash = "commit-head-123",
        ),
        body = "Handle null safely",
        status = CommentStatus.OPEN,
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:31:00+03:00",
    )
}
