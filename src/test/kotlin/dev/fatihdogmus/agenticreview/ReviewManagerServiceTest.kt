package dev.fatihdogmus.agenticreview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.persistence.SavedReviewArchive
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.testutil.gitHead
import dev.fatihdogmus.agenticreview.testutil.initGitRepo
import dev.fatihdogmus.agenticreview.testutil.runGit
import dev.fatihdogmus.agenticreview.testutil.write
import dev.fatihdogmus.agenticreview.vcs.BranchReviewMetadata
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.GitCommandFallback
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestApplication
class ReviewManagerServiceTest {
    private val project by projectFixture()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun resetTurnState() {
        TurnSnapshotService.getInstance(project).clearAll(notify = false)
        ReviewStateService.getInstance(project).setTurnSnapshotsJson("")
        ReviewStateService.getInstance(project).setTurnDiffsJson("")
    }

    @Test
    fun addCommentBuildsMultiLineAnchor() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("multi-line-anchor")
        ReviewStateService.getInstance(project).addReview(review)

        val createdComment = manager.addComment(
            review.id,
            sampleChangedFile("src/Foo.kt"),
            DiffSide.RIGHT,
            2,
            "Need cleanup",
            endLineNumber = 3,
        )

        val comment = manager.findReview(review.id)?.comments?.single()
        assertThat(comment).isNotNull
        assertThat(createdComment).isSameAs(comment)
        assertThat(comment!!.body).isEqualTo("Need cleanup")
        assertThat(comment.anchor.newLine).isEqualTo(2)
        assertThat(comment.anchor.endNewLine).isEqualTo(3)
        assertThat(comment.anchor.selectedText).isEqualTo("two\nthree")
        assertThat(comment.anchor.beforeContext).containsExactly("one")
        assertThat(comment.anchor.afterContext).containsExactly("four")
    }

    @Test
    fun commentsForFileAndDeleteComment() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("delete-comment")
        ReviewStateService.getInstance(project).addReview(review)

        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "foo")
        manager.addComment(review.id, sampleChangedFile("src/Bar.kt"), DiffSide.RIGHT, 2, "bar")

        val fooComments = manager.commentsForFile(review.id, "src/Foo.kt")
        assertThat(fooComments).singleElement().extracting("body").isEqualTo("foo")

        assertThat(manager.deleteComment(fooComments.single().id)).isTrue()

        assertThat(manager.commentsForFile(review.id, "src/Foo.kt")).isEmpty()
        assertThat(manager.findReview(review.id)?.comments).hasSize(1)
        assertThat(manager.findReview(review.id)?.comments?.single()?.body).isEqualTo("bar")
    }

    @Test
    fun commentsForFileReturnsResolvedCommentsToo() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("open-only-comments")
        review.comments += ReviewComment(
            id = "open-comment",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 1),
            body = "open",
            status = CommentStatus.OPEN,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        review.comments += ReviewComment(
            id = "resolved-comment",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 2),
            body = "resolved",
            status = CommentStatus.RESOLVED,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        review.comments += ReviewComment(
            id = "addressed-comment",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 3),
            body = "resolved-too",
            status = CommentStatus.RESOLVED,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        ReviewStateService.getInstance(project).addReview(review)

        val comments = manager.commentsForFile(review.id, "src/Foo.kt")

        assertThat(comments.map { it.id }).containsExactly("open-comment", "resolved-comment", "addressed-comment")
        assertThat(comments.map { it.status })
            .containsExactly(CommentStatus.OPEN, CommentStatus.RESOLVED, CommentStatus.RESOLVED)
    }

    @Test
    fun renameReviewUpdatesTitle() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("rename-review")
        ReviewStateService.getInstance(project).addReview(review)

        manager.renameReview(review.id, "  Better name  ")

        assertThat(manager.findReview(review.id)?.title).isEqualTo("Better name")
    }

    @Test
    fun prepareSaveReviewUsesKebabCaseNameAndReviewId() {
        val manager = ReviewManagerService.getInstance(project)
        val tempDir = Files.createTempDirectory("agentic-review-save")
        val review = seededCommitReview("save-plan").apply {
            target.parentHash = "parent-1"
            target.commitHash = "commit-1"
        }
        review.repositoryRoot = tempDir.toString()
        ReviewStateService.getInstance(project).addReview(review)

        val plan = manager.prepareSaveReview(review.id, "My Review Name")
        val archive = json.decodeFromString<SavedReviewArchive>(plan!!.payload)

        assertThat(plan.title).isEqualTo("My Review Name")
        assertThat(plan.filePath.fileName.toString()).isEqualTo("my-review-name-${review.id}.json")
        assertThat(manager.findReview(review.id)?.title).isEqualTo("My Review Name")
        assertThat(archive.targetType).isEqualTo(ReviewTargetType.COMMIT)
        assertThat(archive.beginCommit).isEqualTo("parent-1")
        assertThat(archive.endCommit).isEqualTo("commit-1")
    }

    @Test
    fun prepareSaveReviewPersistsCommitRangeBoundaries() {
        val manager = ReviewManagerService.getInstance(project)
        val review = Review(
            id = "save-range",
            title = "Range review",
            target = ReviewTarget(type = ReviewTargetType.COMMIT_RANGE, baseRef = "base-2", headRef = "head-2"),
            repositoryRoot = project.basePath!!,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        ReviewStateService.getInstance(project).addReview(review)

        val plan = manager.prepareSaveReview(review.id, review.title)
        val archive = json.decodeFromString<SavedReviewArchive>(plan!!.payload)

        assertThat(archive.targetType).isEqualTo(ReviewTargetType.COMMIT_RANGE)
        assertThat(archive.beginCommit).isEqualTo("base-2")
        assertThat(archive.endCommit).isEqualTo("head-2")
    }

    @Test
    fun renameReviewDoesNotRenameUncommittedReview() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("rename-uncommitted")
        ReviewStateService.getInstance(project).addReview(review)

        manager.renameReview(review.id, "New name")

        assertThat(manager.findReview(review.id)?.title).isEqualTo("Service review")
    }

    @Test
    fun renameReviewIgnoresBlankAndUnchangedTitles() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("rename-noop")
        ReviewStateService.getInstance(project).addReview(review)

        manager.renameReview(review.id, "   ")
        manager.renameReview(review.id, "Commit review")

        assertThat(manager.findReview(review.id)?.title).isEqualTo("Commit review")
    }

    @Test
    fun createUncommittedReviewReusesExistingOne() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }

        manager.openDefaultReview()
        val first = manager.getCurrentReview()
        manager.openDefaultReview()
        val second = manager.getCurrentReview()

        assertThat(first).isNotNull
        assertThat(second?.id).isEqualTo(first?.id)
        assertThat(manager.listReviews().count { it.target.type == ReviewTargetType.UNCOMMITTED }).isEqualTo(1)
        assertThat(first?.target?.commitHash).isEqualTo("head-1")
    }

    @Test
    fun markFileSeenTracksCurrentSnapshotOnlyOnce() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("seen-once")
        ReviewStateService.getInstance(project).addReview(review)

        val firstMarked = manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))
        val secondMarked = manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))

        assertThat(firstMarked).isTrue()
        assertThat(secondMarked).isFalse()
        assertThat(manager.findReview(review.id)?.seenFiles).hasSize(1)
    }

    @Test
    fun syncSeenFilesRemovesStaleSnapshots() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("seen-prune")
        ReviewStateService.getInstance(project).addReview(review)
        manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))

        val changed = manager.syncSeenFiles(
            review.id,
            listOf(sampleChangedFile("src/Foo.kt", afterText = "one\ntwo\nthree\nfour\nfive")),
            notify = false,
        )

        assertThat(changed).isTrue()
        assertThat(manager.findReview(review.id)?.seenFiles).isEmpty()
    }

    @Test
    fun syncUncommittedReviewStateKeepsReviewWhenChangesGone() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { false }
        manager.uncommittedChangesLoader = { emptyList() }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()
        val review = manager.getCurrentReview()!!
        manager.selectReview(review.id)

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isFalse()
        assertThat(manager.findReview(review.id)).isNotNull
        assertThat(manager.getCurrentReview()?.id).isEqualTo(review.id)
    }

    @Test
    fun syncUncommittedReviewStateClearsCommentsWhenChangesGone() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { false }
        manager.uncommittedChangesLoader = { emptyList() }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()
        val review = manager.getCurrentReview()!!
        ReviewStateService.getInstance(project).findReview(review.id)!!.comments += ReviewComment(
            id = "comment-1",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 1),
            body = "stale",
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(manager.findReview(review.id)?.comments).isEmpty()
    }

    @Test
    fun syncUncommittedReviewStateClearsSeenFilesWhenChangesGone() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { false }
        manager.uncommittedChangesLoader = { emptyList() }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()
        val review = manager.getCurrentReview()!!
        manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(manager.findReview(review.id)?.seenFiles).isEmpty()
    }

    @Test
    fun syncUncommittedReviewStateClearsStoredTurnsWhenChangesGone() {
        val manager = ReviewManagerService.getInstance(project)
        val turnService = TurnSnapshotService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()

        turnService.beginTurn("session-clear-empty", "step-clear-empty", project.basePath!!, null, null)
        turnService.endTurn("session-clear-empty", "step-clear-empty", "completed", emptyList(), emptyList())
        manager.hasUncommittedChangesSupplier = { false }
        manager.uncommittedChangesLoader = { emptyList() }

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(turnService.getCompletedTurns()).isEmpty()
        assertThat(ReviewStateService.getInstance(project).turnSnapshotsJson()).contains("\"completedTurns\":[]")
    }

    @Test
    fun syncUncommittedReviewStateClearsCommentsWhenHeadChanges() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()
        val review = manager.getCurrentReview()!!
        ReviewStateService.getInstance(project).findReview(review.id)!!.comments += ReviewComment(
            id = "comment-1",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 1),
            body = "stale after commit",
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        manager.currentHeadHashSupplier = { "head-2" }

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(manager.findReview(review.id)?.comments).isEmpty()
        assertThat(manager.findReview(review.id)?.target?.commitHash).isEqualTo("head-2")
    }

    @Test
    fun syncUncommittedReviewStateClearsSeenFilesWhenHeadChanges() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()
        val review = manager.getCurrentReview()!!
        manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))
        manager.currentHeadHashSupplier = { "head-2" }

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(manager.findReview(review.id)?.seenFiles).isEmpty()
        assertThat(manager.findReview(review.id)?.target?.commitHash).isEqualTo("head-2")
    }

    @Test
    fun syncUncommittedReviewStateClearsStoredTurnsWhenHeadChanges() {
        val manager = ReviewManagerService.getInstance(project)
        val turnService = TurnSnapshotService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }
        manager.openDefaultReview()

        turnService.beginTurn("session-clear-head", "step-clear-head", project.basePath!!, null, null)
        turnService.endTurn("session-clear-head", "step-clear-head", "completed", emptyList(), emptyList())
        manager.currentHeadHashSupplier = { "head-2" }

        val changed = manager.syncUncommittedReviewState()

        assertThat(changed).isTrue()
        assertThat(turnService.getCompletedTurns()).isEmpty()
        assertThat(manager.findReview(manager.getCurrentReview()!!.id)?.target?.commitHash).isEqualTo("head-2")
    }

    @Test
    fun initCreatesPersistentUncommittedReview() {
        val manager = ReviewManagerService.getInstance(project)

        val uncommittedReviews = manager.listReviews().filter { it.target.type == ReviewTargetType.UNCOMMITTED }

        assertThat(uncommittedReviews).hasSize(1)
        assertThat(manager.getCurrentReview()?.id).isEqualTo(uncommittedReviews.single().id)
    }

    @Test
    fun canCreateBranchReviewReflectsMetadataAvailability() {
        val manager = ReviewManagerService.getInstance(project)
        manager.canCreateBranchReviewSupplier = { false }

        assertThat(manager.canCreateBranchReview()).isFalse()

        manager.canCreateBranchReviewSupplier = { true }
        manager.branchReviewMetadataProvider = { sampleBranchReviewMetadata() }

        assertThat(manager.canCreateBranchReview()).isTrue()
    }

    @Test
    fun createBranchReviewCreatesCommitRangeReview() {
        val manager = ReviewManagerService.getInstance(project)
        manager.branchReviewMetadataProvider = { sampleBranchReviewMetadata() }

        val review = manager.createBranchReview()

        assertThat(review.target.type).isEqualTo(ReviewTargetType.COMMIT_RANGE)
        assertThat(review.target.baseRef).isEqualTo("merge-base-123")
        assertThat(review.target.headRef).isEqualTo("head-456")
        assertThat(review.target.subject).isEqualTo("feature/test vs main")
        assertThat(review.title).isEqualTo("feature/test vs main")
    }

    @Test
    fun createBranchReviewFailsWhenMetadataUnavailable() {
        val manager = ReviewManagerService.getInstance(project)
        manager.branchReviewMetadataProvider = { null }

        assertThatThrownBy { manager.createBranchReview() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Branch review unavailable")
    }

    @Test
    fun canSaveReviewCoversNullUncommittedAndCommitReviews() {
        val manager = ReviewManagerService.getInstance(project)

        assertThat(manager.canSaveReview(null)).isFalse()
        assertThat(manager.canSaveReview(seededReview("unsavable"))).isFalse()
        assertThat(manager.canSaveReview(seededCommitReview("savable"))).isTrue()
    }

    @Test
    fun prepareSaveReviewReturnsNullForMissingAndUncommittedReviews() {
        val manager = ReviewManagerService.getInstance(project)
        val uncommitted = seededReview("prepare-null")
        ReviewStateService.getInstance(project).addReview(uncommitted)

        assertThat(manager.prepareSaveReview("missing", "Name")).isNull()
        assertThat(manager.prepareSaveReview(uncommitted.id, "Name")).isNull()
    }

    @Test
    fun getCurrentReviewClearsStaleSelection() {
        val manager = ReviewManagerService.getInstance(project)
        manager.selectReview("missing-review")
        manager.selectFile("src/Foo.kt")

        assertThat(manager.getCurrentReview()).isNull()
        assertThat(manager.currentReviewId).isNull()
        assertThat(manager.currentFilePath).isNull()
    }

    @Test
    fun createCommitRangeReviewRejectsEmptyCommitList() {
        val manager = ReviewManagerService.getInstance(project)

        assertThatThrownBy { manager.createCommitRangeReview(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("commitHashes must not be empty")
    }

    @Test
    fun createCommitRangeReviewSingleCommitFallsBackToCommitReview() {
        val repoRoot = Path.of(project.basePath!!)
        initGitRepo(repoRoot)
        write(repoRoot.resolve("src/Foo.kt"), "one\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "initial")
        val commit = gitHead(repoRoot)
        val manager = ReviewManagerService.getInstance(project)

        val review = manager.createCommitRangeReview(listOf(commit))

        assertThat(review.target.type).isEqualTo(ReviewTargetType.COMMIT)
        assertThat(review.target.commitHash).isEqualTo(commit)
    }

    @Test
    fun createCommitRangeReviewMultipleCommitsCreatesRangeReview() {
        val repoRoot = Path.of(project.basePath!!)
        initGitRepo(repoRoot)
        write(repoRoot.resolve("src/Foo.kt"), "one\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "first")
        val first = gitHead(repoRoot)
        write(repoRoot.resolve("src/Foo.kt"), "two\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "second")
        val second = gitHead(repoRoot)
        write(repoRoot.resolve("src/Foo.kt"), "three\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "third")
        val third = gitHead(repoRoot)
        val manager = ReviewManagerService.getInstance(project)

        val review = manager.createCommitRangeReview(listOf(second, third))

        assertThat(review.target.type).isEqualTo(ReviewTargetType.COMMIT_RANGE)
        assertThat(review.target.baseRef).isEqualTo(first)
        assertThat(review.target.headRef).isEqualTo(third)
    }

    @Test
    fun loadChangedFilesCoversUncommittedCommitAndRangeReviews() {
        val repoRoot = Path.of(project.basePath!!)
        initGitRepo(repoRoot)
        val manager = ReviewManagerService.getInstance(project)
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Uncommitted.kt")) }
        val uncommittedFiles = manager.loadChangedFiles(seededReview("load-uncommitted"))

        write(repoRoot.resolve("src/Foo.kt"), "one\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "first")
        val first = gitHead(repoRoot)
        write(repoRoot.resolve("src/Foo.kt"), "two\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "second")
        val second = gitHead(repoRoot)

        val commitReview =
            seededCommitReview("load-commit").apply { repositoryRoot = repoRoot.toString(); target.commitHash = second }
        val rangeReview = Review(
            id = "review-range-load",
            title = "Range",
            target = ReviewTarget(type = ReviewTargetType.COMMIT_RANGE, baseRef = first, headRef = second),
            repositoryRoot = repoRoot.toString(),
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )

        assertThat(uncommittedFiles).hasSize(1)
        assertThat(manager.loadChangedFiles(commitReview)).hasSize(1)
        assertThat(manager.loadChangedFiles(rangeReview)).hasSize(1)
    }

    @Test
    fun deleteReviewDoesNothingForUncommittedReview() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("delete-uncommitted")
        ReviewStateService.getInstance(project).addReview(review)

        manager.deleteReview(review.id)

        assertThat(manager.findReview(review.id)).isNotNull
    }

    @Test
    fun deleteReviewSelectsUncommittedReviewWhenCurrentCommitReviewIsDeleted() {
        val manager = ReviewManagerService.getInstance(project)
        val uncommitted = seededReview("delete-select-uncommitted")
        val commitReview = seededCommitReview("delete-current")
        ReviewStateService.getInstance(project).addReview(uncommitted)
        ReviewStateService.getInstance(project).addReview(commitReview)
        manager.selectReview(commitReview.id)

        manager.deleteReview(commitReview.id)

        assertThat(manager.findReview(commitReview.id)).isNull()
        assertThat(manager.getCurrentReview()?.target?.type).isEqualTo(ReviewTargetType.UNCOMMITTED)
    }

    @Test
    fun markCommentResolvedWithoutMetadataKeepsAgentMetadataNull() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("resolve-no-meta")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id

        assertThat(manager.markCommentResolved(commentId)).isTrue()

        val comment = manager.findReview(review.id)!!.comments.single()
        assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
        assertThat(comment.agentMetadata).isNull()
    }

    @Test
    fun markCommentResolvedStoresAgentMetadataWhenProvided() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("resolve-meta")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id

        assertThat(
            manager.markCommentResolved(
                commentId,
                message = "done",
                agentName = "opencode",
                runId = "run-1"
            )
        ).isTrue()

        val comment = manager.findReview(review.id)!!.comments.single()
        assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
        assertThat(comment.agentMetadata?.addressedBy).isEqualTo("opencode")
        assertThat(comment.agentMetadata?.message).isNull()
        assertThat(comment.agentMetadata?.runId).isEqualTo("run-1")
        assertThat(comment.agentMetadata?.addressedAt).isNotBlank()
        val reply = comment.replies.single()
        assertThat(reply.body).isEqualTo("done")
        assertThat(reply.kind).isEqualTo(ReplyKind.RESOLUTION)
        assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
        assertThat(reply.author).isEqualTo("opencode")
    }

    @Test
    fun markCommentResolvedOnAlreadyResolvedCommentIsNoOp() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("resolve-twice")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id
        assertThat(manager.markCommentResolved(commentId)).isTrue()
        val updatedAtAfterFirstResolve = manager.findReview(review.id)!!.comments.single().updatedAt

        var notifications = 0
        val listener = { notifications += 1 }
        manager.addListener(listener)
        try {
            assertThat(manager.markCommentResolved(commentId)).isFalse()
        } finally {
            manager.removeListener(listener)
        }

        assertThat(notifications).isZero()
        assertThat(manager.findReview(review.id)!!.comments.single().updatedAt).isEqualTo(updatedAtAfterFirstResolve)
    }

    @Test
    fun markCommentOpenReopensResolvedCommentAndKeepsHistory() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("reopen")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id
        manager.markCommentResolved(commentId, message = "done in a1b2c3", agentName = "codex", runId = "run-1")
        // Pin a stale timestamp so the assertion below proves reopen actually bumps it.
        manager.findReview(review.id)!!.comments.single().updatedAt = "2000-01-01T00:00:00Z"

        assertThat(manager.markCommentOpen(commentId)).isTrue()

        val comment = manager.findReview(review.id)!!.comments.single()
        assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
        assertThat(comment.updatedAt).isNotEqualTo("2000-01-01T00:00:00Z")
        assertThat(comment.replies.map { it.body }).containsExactly("done in a1b2c3")
        assertThat(comment.agentMetadata?.addressedBy).isEqualTo("codex")
    }

    @Test
    fun markCommentOpenReturnsFalseForUnknownIdAndAlreadyOpenComment() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("reopen-noop")
        ReviewStateService.getInstance(project).addReview(review)
        // addComment's own notifyChanged() is dispatched via invokeLater; run it on the EDT here so it is
        // flushed before the listener below is registered, instead of racing in asynchronously afterwards.
        ApplicationManager.getApplication().invokeAndWait {
            manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        }
        val commentId = manager.findReview(review.id)!!.comments.single().id

        var notifications = 0
        val listener = { notifications += 1 }
        manager.addListener(listener)
        try {
            assertThat(manager.markCommentOpen("missing")).isFalse()
            assertThat(manager.markCommentOpen(commentId)).isFalse()
        } finally {
            manager.removeListener(listener)
        }

        assertThat(notifications).isZero()
    }

    @Test
    fun seenFileKeysReturnsEmptyForMissingReviewAndCurrentKeysForExistingReview() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("seen-keys")
        ReviewStateService.getInstance(project).addReview(review)
        manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))

        assertThat(manager.seenFileKeys("missing")).isEmpty()
        assertThat(manager.seenFileKeys(review.id)).hasSize(1)
    }

    @Test
    fun buildAgentPromptReturnsNullForMissingReview() {
        val manager = ReviewManagerService.getInstance(project)
        assertThat(manager.buildAgentPrompt("missing")).isNull()
    }

    @Test
    fun updateCommentUpdatesBodyAndTimestamp() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("update-comment")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "before")
        val comment = manager.findReview(review.id)!!.comments.single()
        val previousUpdatedAt = comment.updatedAt

        manager.updateComment(comment.id, "after")

        val updated = manager.findReview(review.id)!!.comments.single()
        assertThat(updated.body).isEqualTo("after")
        assertThat(updated.updatedAt).isNotEqualTo(previousUpdatedAt)
    }

    @Test
    fun deleteCommentDoesNothingForMissingId() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("delete-missing-comment")
        ReviewStateService.getInstance(project).addReview(review)

        assertThat(manager.deleteComment("missing")).isFalse()

        assertThat(manager.findReview(review.id)?.comments).isEmpty()
    }

    @Test
    fun commentsForFileReturnsSortedCommentsOfAllStatuses() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("comments-sorted")
        review.comments += ReviewComment(
            id = "c2",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 5),
            body = "later",
            status = CommentStatus.OPEN,
            createdAt = "2026-05-07T14:21:00+03:00",
            updatedAt = "2026-05-07T14:21:00+03:00",
        )
        review.comments += ReviewComment(
            id = "c1",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 2),
            body = "earlier",
            status = CommentStatus.OPEN,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
        )
        review.comments += ReviewComment(
            id = "resolved",
            reviewId = review.id,
            filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 1),
            body = "resolved",
            status = CommentStatus.RESOLVED,
            createdAt = "2026-05-07T14:19:00+03:00",
            updatedAt = "2026-05-07T14:19:00+03:00",
        )
        ReviewStateService.getInstance(project).addReview(review)

        val comments = manager.commentsForFile(review.id, "src/Foo.kt")

        // Sorted by anchor line regardless of status: resolved (line 1), c1 (line 2), c2 (line 5).
        assertThat(comments.map { it.id }).containsExactly("resolved", "c1", "c2")
    }

    @Test
    fun ensureUncommittedReviewKeepsCurrentSelectionWhenAlreadySet() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("preserve-selection")
        ReviewStateService.getInstance(project).addReview(review)
        manager.selectReview(review.id)
        manager.hasUncommittedChangesSupplier = { true }
        manager.uncommittedChangesLoader = { listOf(sampleChangedFile("src/Foo.kt")) }
        manager.repositoryRootResolver = { "/tmp/repo" }
        manager.currentHeadHashSupplier = { "head-1" }

        manager.openDefaultReview()

        assertThat(manager.getCurrentReview()?.target?.type).isEqualTo(ReviewTargetType.UNCOMMITTED)
    }

    @Test
    fun syncSeenFilesReturnsFalseWhenNothingChanges() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("sync-seen-noop")
        ReviewStateService.getInstance(project).addReview(review)
        manager.markFileSeen(review.id, sampleChangedFile("src/Foo.kt"))

        val changed = manager.syncSeenFiles(
            review.id,
            listOf(sampleChangedFile("src/Foo.kt")),
            notify = false,
        )

        assertThat(changed).isFalse()
        assertThat(manager.findReview(review.id)?.seenFiles).hasSize(1)
    }

    @Test
    fun addReplyAppendsWithoutChangingStatus() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("add-reply")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        val reply = manager.addReply(
            commentId = comment.id,
            body = "guarded at L42",
            author = "codex",
            authorKind = ReplyAuthorKind.AGENT,
        )

        assertThat(reply).isNotNull
        assertThat(reply!!.id).isNotBlank()
        assertThat(reply.commentId).isEqualTo(comment.id)
        assertThat(reply.author).isEqualTo("codex")
        assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
        assertThat(reply.kind).isEqualTo(ReplyKind.COMMENT)
        assertThat(reply.createdAt).isNotBlank()
        assertThat(comment.replies).containsExactly(reply)
        assertThat(comment.status).isEqualTo(CommentStatus.OPEN)
    }

    @Test
    fun addReplyDefaultsToHumanAuthor() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("human-reply")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")
        val nonGitDir = Files.createTempDirectory("agentic-review-human-author-non-git")
        manager.repositoryRootResolver = { nonGitDir.toString() }

        val reply = manager.addReply(comment.id, "ah, missed that")

        assertThat(reply).isNotNull
        assertThat(reply!!.authorKind).isEqualTo(ReplyAuthorKind.HUMAN)
        assertThat(reply.author).isNotBlank()
        // git config still resolves to a global/system-level user.name outside a repo on some
        // machines, so accept either leg of the documented fallback chain rather than assuming
        // the git lookup always fails for a non-repo directory.
        val gitConfigName = GitCommandFallback(nonGitDir.toString())
            .runOrNull("config", "user.name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val systemPropertyName = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
        assertThat(reply.author).isIn(listOfNotNull(gitConfigName, systemPropertyName))
    }

    @Test
    fun addReplyUsesGitConfigUserNameWhenAvailable() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("human-reply-git-config")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")
        val gitDir = Files.createTempDirectory("agentic-review-human-author-git-config")
        initGitRepo(gitDir)
        val sentinel = "Sentinel Author ${System.nanoTime()}"
        runGit(gitDir, "config", "user.name", sentinel)
        manager.repositoryRootResolver = { gitDir.toString() }

        val reply = manager.addReply(comment.id, "picking this up")

        assertThat(reply).isNotNull
        assertThat(reply!!.authorKind).isEqualTo(ReplyAuthorKind.HUMAN)
        assertThat(reply.author).isEqualTo(sentinel)
    }

    @Test
    fun addReplyDefaultsToAgentAuthorForAgentKind() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("agent-reply-default")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        val reply = manager.addReply(comment.id, "will fix", author = null, authorKind = ReplyAuthorKind.AGENT)

        assertThat(reply).isNotNull
        assertThat(reply!!.author).isEqualTo("agent")
        assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
    }

    @Test
    fun addReplyUpdatesCommentAndReviewTimestampsAndNotifiesListeners() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("reply-touch")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")
        val commentUpdatedAtBefore = comment.updatedAt
        val reviewUpdatedAtBefore = manager.findReview(review.id)!!.updatedAt
        var notified = false
        val listener = { notified = true }
        manager.addListener(listener)

        try {
            ApplicationManager.getApplication().invokeAndWait {
                manager.addReply(comment.id, "noted")
            }

            assertThat(comment.updatedAt).isNotEqualTo(commentUpdatedAtBefore)
            assertThat(manager.findReview(review.id)!!.updatedAt).isNotEqualTo(reviewUpdatedAtBefore)
            assertThat(notified).isTrue()
        } finally {
            manager.removeListener(listener)
        }
    }

    @Test
    fun addReplyPreservesOrderAndRejectsUnknownComment() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("reply-order")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        manager.addReply(comment.id, "first")
        manager.addReply(comment.id, "second")

        assertThat(comment.replies.map { it.body }).containsExactly("first", "second")
        assertThat(manager.addReply("does-not-exist", "orphan")).isNull()
    }

    @Test
    fun addReplyRejectsBlankBodyWithoutMutating() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("blank-reply")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        assertThat(manager.addReply(comment.id, "   ")).isNull()
        assertThat(comment.replies).isEmpty()
    }

    @Test
    fun addReplyToResolvedCommentDoesNotReopenIt() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("reply-resolved")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")
        manager.markCommentResolved(comment.id)

        manager.addReply(comment.id, "one more thought")

        assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
        assertThat(comment.replies.map { it.body }).containsExactly("one more thought")
    }

    @Test
    fun markCommentResolvedAppendsResolutionReply() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("resolve-reply")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        manager.markCommentResolved(comment.id, message = "fixed in a1b2c3", agentName = "codex", runId = "run-7")

        assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
        val reply = comment.replies.single()
        assertThat(reply.body).isEqualTo("fixed in a1b2c3")
        assertThat(reply.author).isEqualTo("codex")
        assertThat(reply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
        assertThat(reply.kind).isEqualTo(ReplyKind.RESOLUTION)
        assertThat(reply.runId).isEqualTo("run-7")
        assertThat(comment.agentMetadata?.message).isNull()
        assertThat(comment.agentMetadata?.addressedBy).isEqualTo("codex")
        assertThat(comment.agentMetadata?.runId).isEqualTo("run-7")
    }

    @Test
    fun markCommentResolvedWithoutMessageAddsNoReply() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("resolve-no-message")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        manager.markCommentResolved(comment.id)

        assertThat(comment.status).isEqualTo(CommentStatus.RESOLVED)
        assertThat(comment.replies).isEmpty()
    }

    @Test
    fun repeatedResolutionMessagesPreserveEarlierReplies() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("resolve-twice")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        manager.markCommentResolved(comment.id, message = "first pass")
        manager.markCommentResolved(comment.id, message = "second pass")

        assertThat(comment.replies.map { it.body }).containsExactly("first pass", "second pass")
    }

    @Test
    fun savedArchiveRoundTripsReplies() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("archive-replies")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")
        manager.addReply(comment.id, "guarded at L42", author = "codex", authorKind = ReplyAuthorKind.AGENT)

        val plan = manager.prepareSaveReview(review.id, review.title) ?: error("save plan missing")
        val archive = json.decodeFromString<SavedReviewArchive>(plan.payload)

        val savedReply = archive.comments.single().replies.single()
        assertThat(savedReply.body).isEqualTo("guarded at L42")
        assertThat(savedReply.authorKind).isEqualTo(ReplyAuthorKind.AGENT)
    }

    @Test
    fun concurrentAddReplyCallsDoNotLoseReplies() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededReview("concurrent-reply")
        ReviewStateService.getInstance(project).addReview(review)
        val comment = manager.addComment(
            review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "needs fix",
        ) ?: error("comment missing")

        val threadCount = 8
        val repliesPerThread = 200
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val threads = (0 until threadCount).map { threadId ->
            Thread {
                startLatch.await()
                try {
                    repeat(repliesPerThread) { i ->
                        manager.addReply(comment.id, "reply-$threadId-$i")
                    }
                } finally {
                    doneLatch.countDown()
                }
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        startLatch.countDown()
        assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue()
        threads.forEach { it.join(30_000) }
        assertThat(threads.any { it.isAlive }).isFalse()

        val expectedBodies = (0 until threadCount).flatMap { threadId ->
            (0 until repliesPerThread).map { i -> "reply-$threadId-$i" }
        }.toSet()

        assertThat(comment.replies).hasSize(threadCount * repliesPerThread)
        assertThat(comment.replies.map { it.body }.toSet()).isEqualTo(expectedBodies)
    }

    @Test
    fun resolvedCommentExpandStateDefaultsCollapsedAndRoundTrips() {
        val manager = ReviewManagerService.getInstance(project)

        assertThat(manager.isResolvedCommentExpanded("c-1")).isFalse()

        manager.setResolvedCommentExpanded("c-1", true)
        assertThat(manager.isResolvedCommentExpanded("c-1")).isTrue()
        assertThat(manager.isResolvedCommentExpanded("c-2")).isFalse()

        manager.setResolvedCommentExpanded("c-1", false)
        assertThat(manager.isResolvedCommentExpanded("c-1")).isFalse()
    }

    @Test
    fun settingExpandStateDoesNotNotifyListeners() {
        val manager = ReviewManagerService.getInstance(project)
        var notifications = 0
        val listener = { notifications += 1 }
        manager.addListener(listener)
        try {
            manager.setResolvedCommentExpanded("c-1", true)
            manager.setResolvedCommentExpanded("c-1", false)
        } finally {
            manager.removeListener(listener)
        }
        assertThat(notifications).isZero()
    }

    @Test
    fun statusTransitionResetsExpandState() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("expand-reset")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id
        manager.markCommentResolved(commentId)

        manager.setResolvedCommentExpanded(commentId, true)
        assertThat(manager.markCommentOpen(commentId)).isTrue()
        assertThat(manager.isResolvedCommentExpanded(commentId)).isFalse()

        manager.setResolvedCommentExpanded(commentId, true)
        assertThat(manager.markCommentResolved(commentId)).isTrue()
        assertThat(manager.isResolvedCommentExpanded(commentId)).isFalse()
    }

    @Test
    fun expandStateIsAlreadyResetWhenListenersAreNotified() {
        // Guards the ordering inside setCommentStatus: the reset must happen BEFORE touch(),
        // because on the EDT the listener rebuilds the UI synchronously and must see the reset.
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("expand-reset-ordering")
        ReviewStateService.getInstance(project).addReview(review)
        val commentId = ApplicationManager.getApplication().let { application ->
            var id = ""
            application.invokeAndWait {
                manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
                id = manager.findReview(review.id)!!.comments.single().id
                manager.markCommentResolved(id)
            }
            id
        }
        manager.setResolvedCommentExpanded(commentId, true)

        val expandedSeenByListener = mutableListOf<Boolean>()
        val listener = { expandedSeenByListener += manager.isResolvedCommentExpanded(commentId) }
        manager.addListener(listener)
        try {
            ApplicationManager.getApplication().invokeAndWait {
                assertThat(manager.markCommentOpen(commentId)).isTrue()
            }
        } finally {
            manager.removeListener(listener)
        }

        assertThat(expandedSeenByListener).containsExactly(false)
    }

    @Test
    fun noOpStatusCallPreservesExpandState() {
        val manager = ReviewManagerService.getInstance(project)
        val review = seededCommitReview("expand-preserve")
        ReviewStateService.getInstance(project).addReview(review)
        manager.addComment(review.id, sampleChangedFile("src/Foo.kt"), DiffSide.RIGHT, 1, "todo")
        val commentId = manager.findReview(review.id)!!.comments.single().id
        manager.markCommentResolved(commentId)
        manager.setResolvedCommentExpanded(commentId, true)

        assertThat(manager.markCommentResolved(commentId)).isFalse()

        assertThat(manager.isResolvedCommentExpanded(commentId)).isTrue()
    }

    private fun seededReview(suffix: String): Review = Review(
        id = "review-service-$suffix",
        title = "Service review",
        target = ReviewTarget(type = ReviewTargetType.UNCOMMITTED),
        repositoryRoot = "/tmp/repo",
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:20:00+03:00",
    )

    private fun seededCommitReview(suffix: String): Review = Review(
        id = "review-commit-$suffix",
        title = "Commit review",
        target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "abc123"),
        repositoryRoot = "/tmp/repo",
        createdAt = "2026-05-07T14:20:00+03:00",
        updatedAt = "2026-05-07T14:20:00+03:00",
    )

    private fun sampleChangedFile(
        path: String,
        beforeText: String = "zero\none\nold-three\nfour",
        afterText: String = "one\ntwo\nthree\nfour",
    ): ChangedFile = ChangedFile(
        filePath = path,
        status = ChangedFileStatus.MODIFIED,
        beforeContent = ReviewContent(
            text = beforeText,
            revisionTitle = "before",
            filePath = path,
        ),
        afterContent = ReviewContent(
            text = afterText,
            revisionTitle = "after",
            filePath = path,
        ),
    )

    private fun sampleBranchReviewMetadata(): BranchReviewMetadata = BranchReviewMetadata(
        repositoryRoot = "/tmp/repo",
        currentBranch = "feature/test",
        baseBranch = "main",
        mergeBase = "merge-base-123",
        headHash = "head-456",
        title = "feature/test vs main",
    )
}
