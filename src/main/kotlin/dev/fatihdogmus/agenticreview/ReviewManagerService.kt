package dev.fatihdogmus.agenticreview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import dev.fatihdogmus.agenticreview.diff.DiffContextExtractor
import dev.fatihdogmus.agenticreview.editor.ReviewPageManager
import dev.fatihdogmus.agenticreview.export.AgentPromptBuilder
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.ReviewLoadResult
import dev.fatihdogmus.agenticreview.persistence.ReviewSavePlan
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.persistence.SavedReviewArchive
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.util.nowIso
import dev.fatihdogmus.agenticreview.vcs.*
import git4idea.repo.GitRepositoryManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.valiktor.ConstraintViolationException
import org.valiktor.functions.isNotBlank
import org.valiktor.functions.isNotNull
import org.valiktor.validate
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

@Service(Service.Level.PROJECT)
class ReviewManagerService(private val project: Project) : Disposable {
    private val stateService = ReviewStateService.getInstance(project)
    private val turnSnapshotService = TurnSnapshotService.getInstance(project)
    private val diffContextExtractor = DiffContextExtractor()
    private val listeners = mutableSetOf<() -> Unit>()
    private val archiveJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    internal var uncommittedChangesLoader: () -> List<ChangedFile> =
        { UncommittedChangesProvider(project).getChangedFiles() }
    internal var repositoryRootResolver: () -> String = { GitRepositoryResolver(project).resolveRepositoryRoot() }
    internal var canCreateBranchReviewSupplier: () -> Boolean =
        { CommitChangesProvider(project).canCreateCurrentBranchReview() }
    internal var branchReviewMetadataProvider: () -> BranchReviewMetadata? =
        { CommitChangesProvider(project).getCurrentBranchReviewMetadataOrNull() }
    internal var isCommitReachableOnCurrentBranchSupplier: (String) -> Boolean = { commitHash ->
        GitCommandFallback(repositoryRootResolver()).runOrNull(
            "merge-base",
            "--is-ancestor",
            commitHash,
            "HEAD"
        ) != null
    }
    internal var currentHeadHashSupplier: () -> String? = {
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentRevision
    }
    internal var hasUncommittedChangesSupplier: () -> Boolean = {
        val changeListManager = ChangeListManager.getInstance(project)
        changeListManager.defaultChangeList.changes.isNotEmpty() || changeListManager.unversionedFilesPaths.isNotEmpty()
    }

    var currentReviewId: String? = null
        private set

    var currentFilePath: String? = null
        private set

    init {
        project.messageBus.connect(this).subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() {
                syncUncommittedReviewState()
            }
        })
        ensureUncommittedReview()
    }

    fun listReviews(status: ReviewStatus? = null): List<Review> = stateService.reviews()
        .asSequence()
        .filter { status == null || it.status == status }
        .sortedByDescending { it.updatedAt }
        .toList()

    fun getCurrentReview(): Review? {
        val reviewId = currentReviewId ?: return null
        val review = stateService.findReview(reviewId)
        if (review == null) {
            currentReviewId = null
            currentFilePath = null
        }
        return review
    }

    fun findReview(reviewId: String): Review? = stateService.findReview(reviewId)

    fun selectReview(reviewId: String?) {
        if (currentReviewId == reviewId) return
        currentReviewId = reviewId
        if (reviewId == null) currentFilePath = null
        notifyChanged()
    }

    fun selectFile(filePath: String?) {
        if (currentFilePath == filePath) return
        currentFilePath = filePath
    }

    fun openDefaultReview() {
        val review = ensureUncommittedReview()
        currentReviewId = review.id
        currentFilePath = null
        if (ApplicationManager.getApplication().isUnitTestMode) {
            notifyChanged()
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ReviewPageManager.getInstance(project).open()
            }
            notifyChanged()
        }
    }

    fun createCommitReview(commitHash: String): Review {
        val metadata = CommitChangesProvider(project).getCommitMetadata(commitHash)
        val review = Review(
            id = UUID.randomUUID().toString(),
            title = "${metadata.shortHash} ${metadata.subject}",
            target = ReviewTarget(
                type = ReviewTargetType.COMMIT,
                commitHash = metadata.hash,
                parentHash = metadata.firstParentHash,
                subject = metadata.subject,
            ),
            repositoryRoot = metadata.repositoryRoot,
            createdAt = nowIso(),
            updatedAt = nowIso(),
        )
        stateService.addReview(review)
        openReview(review.id)
        return review
    }

    fun createCommitRangeReview(commitHashes: List<String>): Review {
        require(commitHashes.isNotEmpty()) { "commitHashes must not be empty" }
        if (commitHashes.size == 1) return createCommitReview(commitHashes.single())

        val metadata = CommitChangesProvider(project).getCombinedCommitMetadata(commitHashes)
        val review = Review(
            id = UUID.randomUUID().toString(),
            title = metadata.title,
            target = ReviewTarget(
                type = ReviewTargetType.COMMIT_RANGE,
                baseRef = metadata.baseHash,
                headRef = metadata.headHash,
            ),
            repositoryRoot = metadata.repositoryRoot,
            createdAt = nowIso(),
            updatedAt = nowIso(),
        )
        stateService.addReview(review)
        openReview(review.id)
        return review
    }

    fun canCreateBranchReview(): Boolean = canCreateBranchReviewSupplier()

    fun createBranchReview(): Review {
        val metadata = branchReviewMetadataProvider() ?: error("Branch review unavailable")
        val review = Review(
            id = UUID.randomUUID().toString(),
            title = metadata.title,
            target = ReviewTarget(
                type = ReviewTargetType.COMMIT_RANGE,
                baseRef = metadata.mergeBase,
                headRef = metadata.headHash,
                subject = "${metadata.currentBranch} vs ${metadata.baseBranch}",
                branchName = metadata.currentBranch,
                baseBranchName = metadata.baseBranch,
            ),
            repositoryRoot = metadata.repositoryRoot,
            createdAt = nowIso(),
            updatedAt = nowIso(),
        )
        stateService.addReview(review)
        openReview(review.id)
        return review
    }

    fun canRefreshBranchReview(review: Review?): Boolean = review?.target?.let { target ->
        target.type == ReviewTargetType.COMMIT_RANGE &&
            (target.branchName != null && target.baseBranchName != null ||
                // Branch reviews created before branch identity was stored used this subject.
                target.subject?.matches(Regex(".+ vs (main|master)")) == true)
    } == true

    fun refreshBranchReview(reviewId: String): Boolean {
        val review = findReview(reviewId)?.takeIf(::canRefreshBranchReview) ?: return false
        val metadata = branchReviewMetadataProvider() ?: return false
        if (review.repositoryRoot != metadata.repositoryRoot) return false
        val branchName = review.target.branchName ?: review.target.subject?.substringBeforeLast(" vs ")
        val baseBranchName = review.target.baseBranchName ?: review.target.subject?.substringAfterLast(" vs ")
        if (branchName != metadata.currentBranch || baseBranchName != metadata.baseBranch) return false

        review.target.baseRef = metadata.mergeBase
        review.target.headRef = metadata.headHash
        review.target.branchName = metadata.currentBranch
        review.target.baseBranchName = metadata.baseBranch
        // The file snapshots will be reloaded by the panel; keep comments and review identity.
        touch(review)
        return true
    }

    fun canSaveReview(review: Review?): Boolean = review != null && review.target.type != ReviewTargetType.UNCOMMITTED

    fun renameReview(reviewId: String, newTitle: String) {
        val review = findReview(reviewId) ?: return
        if (review.target.type == ReviewTargetType.UNCOMMITTED) return
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle == review.title) return
        review.title = trimmedTitle
        touch(review)
    }

    fun prepareSaveReview(reviewId: String, newTitle: String): ReviewSavePlan? {
        if (!canSaveReview(findReview(reviewId))) return null
        renameReview(reviewId, newTitle)
        val review = findReview(reviewId) ?: return null
        val archive = SavedReviewArchive(
            originalReviewId = review.id,
            title = review.title,
            targetType = review.target.type,
            beginCommit = review.target.beginCommit(),
            endCommit = review.target.endCommit(),
            subject = review.target.subject,
            branchName = review.target.branchName,
            baseBranchName = review.target.baseBranchName,
            reviewStatus = review.status,
            createdAt = review.createdAt,
            updatedAt = review.updatedAt,
            comments = review.comments.toList(),
        )
        val filePath = reviewArchivePath(review)
        return ReviewSavePlan(
            reviewId = review.id,
            title = review.title,
            filePath = filePath,
            payload = archiveJson.encodeToString(archive),
            fileExists = Files.exists(filePath),
        )
    }

    fun saveReviewToFile(plan: ReviewSavePlan) {
        Files.createDirectories(plan.filePath.parent)
        Files.writeString(plan.filePath, plan.payload)
    }

    fun loadReviewFromFile(filePath: Path): ReviewLoadResult = runCatching {
        val archive = archiveJson.decodeFromString<SavedReviewArchive>(Files.readString(filePath)).validatedForImport()
        val beginCommit = archive.beginCommit
        val endCommit = archive.endCommit
        if (beginCommit != null && !isCommitReachableOnCurrentBranchSupplier(beginCommit)) {
            return ReviewLoadResult(error = "Begin commit is not reachable from current branch: $beginCommit")
        }
        if (endCommit != null && !isCommitReachableOnCurrentBranchSupplier(endCommit)) {
            return ReviewLoadResult(error = "End commit is not reachable from current branch: $endCommit")
        }

        val reviewId = UUID.randomUUID().toString()
        val now = nowIso()
        val review = Review(
            id = reviewId,
            title = archive.title,
            target = archive.toReviewTarget(),
            repositoryRoot = repositoryRootResolver(),
            createdAt = archive.createdAt.ifBlank { now },
            updatedAt = archive.updatedAt.ifBlank { now },
            status = archive.reviewStatus,
            comments = archive.comments.map { comment ->
                comment.copy(id = UUID.randomUUID().toString(), reviewId = reviewId)
            }.toMutableList(),
        )
        stateService.addReview(review)
        openReview(review.id)
        ReviewLoadResult(reviewId = review.id)
    }.getOrElse { error ->
        if (error is MalformedImportedReviewException || error is SerializationException || error is ConstraintViolationException) {
            return ReviewLoadResult(error = "The imported format is malformed")
        }
        ReviewLoadResult(error = error.message ?: "Failed to load review")
    }

    fun deleteReview(reviewId: String) {
        if (findReview(reviewId)?.target?.type == ReviewTargetType.UNCOMMITTED) return
        stateService.removeReview(reviewId)
        if (currentReviewId == reviewId) {
            currentReviewId = findUncommittedReview()?.id
            currentFilePath = null
        }
        notifyChanged()
    }

    fun loadChangedFiles(review: Review): List<ChangedFile> = when (review.target.type) {
        ReviewTargetType.UNCOMMITTED -> {
            currentUncommittedChanges()
        }

        ReviewTargetType.COMMIT -> CommitChangesProvider(project).getChangedFiles(
            review.target.commitHash ?: error("Commit hash missing")
        )

        ReviewTargetType.COMMIT_RANGE -> CommitChangesProvider(project).getChangedFilesForRange(
            review.target.baseRef ?: error("Base ref missing"),
            review.target.headRef ?: error("Head ref missing"),
        )
    }

    fun addComment(
        reviewId: String,
        changedFile: ChangedFile,
        side: DiffSide,
        lineNumber: Int,
        body: String,
        endLineNumber: Int? = null,
    ): ReviewComment? {
        val review = findReview(reviewId) ?: return null
        val comment = ReviewComment(
            id = UUID.randomUUID().toString(),
            reviewId = reviewId,
            filePath = changedFile.filePath,
            anchor = diffContextExtractor.buildAnchor(
                changedFile,
                side,
                lineNumber,
                review.target.commitHashIfAny(),
                endLineNumber
            ),
            body = body,
            status = CommentStatus.OPEN,
            createdAt = nowIso(),
            updatedAt = nowIso(),
        )
        review.comments.add(comment)
        touch(review)
        return comment
    }

    fun updateComment(commentId: String, body: String) {
        val (review, comment) = findComment(commentId) ?: return
        comment.body = body
        comment.updatedAt = nowIso()
        touch(review)
    }

    fun deleteComment(commentId: String): Boolean {
        val (review, comment) = findComment(commentId) ?: return false
        if (review.comments.remove(comment)) {
            touch(review)
            return true
        }
        return false
    }

    fun markCommentResolved(
        commentId: String,
        message: String? = null,
        agentName: String? = null,
        runId: String? = null
    ): Boolean {
        val (_, comment) = findComment(commentId) ?: return false
        comment.agentMetadata = if (message != null || agentName != null || runId != null) {
            AgentMetadata(
                addressedBy = agentName,
                addressedAt = nowIso(),
                message = message,
                runId = runId,
            )
        } else {
            comment.agentMetadata
        }
        return setCommentStatus(commentId, CommentStatus.RESOLVED)
    }

    fun commentsForFile(reviewId: String, filePath: String): List<ReviewComment> =
        findReview(reviewId)
            ?.comments
            ?.asSequence()
            ?.filter { it.filePath == filePath && it.status == CommentStatus.OPEN }
            ?.sortedWith(compareBy({ it.anchor.newLine ?: it.anchor.oldLine ?: Int.MAX_VALUE }, { it.createdAt }))
            ?.toList()
            .orEmpty()

    fun seenFileKeys(reviewId: String): Set<String> =
        findReview(reviewId)?.seenFiles?.asSequence()?.map { it.key }?.toSet().orEmpty()

    fun markFileSeen(reviewId: String, changedFile: ChangedFile): Boolean {
        val review = findReview(reviewId) ?: return false
        val key = changedFile.seenKey()
        if (review.seenFiles.any { it.key == key }) return false

        review.seenFiles.removeIf { it.filePath == changedFile.filePath }
        review.seenFiles += SeenFileState(
            key = key,
            filePath = changedFile.filePath,
            seenAt = nowIso(),
        )
        return true
    }

    internal fun syncSeenFiles(reviewId: String, changedFiles: List<ChangedFile>, notify: Boolean = true): Boolean {
        val review = findReview(reviewId) ?: return false
        val activeKeys = changedFiles.asSequence().map { it.seenKey() }.toSet()
        val removed = review.seenFiles.removeIf { it.key !in activeKeys }
        if (!removed) return false

        review.updatedAt = nowIso()
        if (notify) notifyChanged()
        return true
    }

    fun findCommentWithReview(commentId: String): Pair<Review, ReviewComment>? = findComment(commentId)

    fun buildAgentPrompt(reviewId: String): String? = findReview(reviewId)?.let { AgentPromptBuilder().build(it) }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    override fun dispose() {
        listeners.clear()
    }

    fun openReview(reviewId: String) {
        currentReviewId = reviewId
        currentFilePath = null
        if (ApplicationManager.getApplication().isUnitTestMode) {
            notifyChanged()
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ReviewPageManager.getInstance(project).open()
            }
            notifyChanged()
        }
    }

    private fun setCommentStatus(commentId: String, status: CommentStatus): Boolean {
        val (review, comment) = findComment(commentId) ?: return false
        comment.status = status
        comment.updatedAt = nowIso()
        touch(review)
        return true
    }

    internal fun syncUncommittedReviewState(notify: Boolean = true): Boolean {
        val uncommittedReviews = stateService.reviews().filter { it.target.type == ReviewTargetType.UNCOMMITTED }
        val keepReview = uncommittedReviews.maxByOrNull { it.updatedAt } ?: createNewUncommittedReview()
        val removedIds = uncommittedReviews.filter { it.id != keepReview.id }.map { it.id }
        val hasChanges = hasUncommittedChanges()
        val currentHeadHash = currentHeadHashSupplier()
        val headChanged = currentHeadHash != null && currentHeadHash != keepReview.target.commitHash
        val shouldClearUncommittedState = headChanged || !hasChanges
        val clearedComments = if (shouldClearUncommittedState && keepReview.comments.isNotEmpty()) {
            keepReview.comments.clear()
            true
        } else {
            false
        }
        val clearedSeenFiles = if (shouldClearUncommittedState && keepReview.seenFiles.isNotEmpty()) {
            keepReview.seenFiles.clear()
            true
        } else {
            false
        }
        val clearedTurns = if (shouldClearUncommittedState && turnSnapshotService.hasStoredTurns()) {
            turnSnapshotService.clearAll(notify = false)
            true
        } else {
            false
        }
        if (headChanged) {
            keepReview.target.commitHash = currentHeadHash
        }
        if (headChanged || clearedComments || clearedSeenFiles || clearedTurns) {
            keepReview.updatedAt = nowIso()
        }
        val shouldSelectUncommitted = currentReviewId == null
        if ((!hasChanges || headChanged) && currentReviewId == keepReview.id) {
            currentFilePath = null
        }
        if (removedIds.isEmpty() && !shouldSelectUncommitted && !clearedComments && !clearedSeenFiles && !clearedTurns && !headChanged) return false

        removedIds.forEach(stateService::removeReview)

        if (currentReviewId in removedIds || shouldSelectUncommitted) {
            currentReviewId = keepReview.id
            currentFilePath = null
        }

        if (notify) notifyChanged()
        return true
    }

    private fun findUncommittedReview(): Review? =
        stateService.reviews().firstOrNull { it.target.type == ReviewTargetType.UNCOMMITTED }

    private fun hasUncommittedChanges(): Boolean = hasUncommittedChangesSupplier()

    private fun currentUncommittedChanges(): List<ChangedFile> =
        runCatching { uncommittedChangesLoader() }.getOrElse { emptyList() }

    private fun reviewArchivePath(review: Review): Path =
        Path.of(review.repositoryRoot, ".agentic-review", "${review.title.toKebabCase()}-${review.id}.json")

    private fun ensureUncommittedReview(): Review {
        syncUncommittedReviewState(notify = false)
        return findUncommittedReview() ?: createNewUncommittedReview().also {
            if (currentReviewId == null) {
                currentReviewId = it.id
            }
        }
    }

    private fun createNewUncommittedReview(): Review {
        val now = nowIso()
        val review = Review(
            id = UUID.randomUUID().toString(),
            title = "Uncommitted changes",
            target = ReviewTarget(
                type = ReviewTargetType.UNCOMMITTED,
                baseRef = "HEAD",
                headRef = "WORKTREE",
                commitHash = currentHeadHashSupplier()
            ),
            repositoryRoot = repositoryRootResolver(),
            createdAt = now,
            updatedAt = now,
        )
        stateService.addReview(review)
        return review
    }

    private fun findComment(commentId: String): Pair<Review, ReviewComment>? {
        stateService.reviews().forEach { review ->
            review.comments.firstOrNull { it.id == commentId }?.let { return review to it }
        }
        return null
    }

    private fun touch(review: Review) {
        review.updatedAt = nowIso()
        notifyChanged()
    }

    private fun notifyChanged() {
        val notifyListeners = {
            listeners.toList().forEach { it() }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            notifyListeners()
        } else {
            application.invokeLater {
                if (!project.isDisposed) {
                    notifyListeners()
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): ReviewManagerService = project.getService(ReviewManagerService::class.java)
    }
}

private fun SavedReviewArchive.toReviewTarget(): ReviewTarget = when (targetType) {
    ReviewTargetType.COMMIT -> ReviewTarget(
        type = ReviewTargetType.COMMIT,
        commitHash = endCommit,
        parentHash = beginCommit,
        subject = subject,
    )

    ReviewTargetType.COMMIT_RANGE -> ReviewTarget(
        type = ReviewTargetType.COMMIT_RANGE,
        baseRef = beginCommit,
        headRef = endCommit,
        subject = subject,
        branchName = branchName,
        baseBranchName = baseBranchName,
    )

    ReviewTargetType.UNCOMMITTED -> ReviewTarget(type = ReviewTargetType.UNCOMMITTED)
}

private fun ReviewTarget.beginCommit(): String? = when (type) {
    ReviewTargetType.COMMIT -> parentHash
    ReviewTargetType.COMMIT_RANGE -> baseRef
    ReviewTargetType.UNCOMMITTED -> null
}

private fun ReviewTarget.endCommit(): String? = when (type) {
    ReviewTargetType.COMMIT -> commitHash
    ReviewTargetType.COMMIT_RANGE -> headRef
    ReviewTargetType.UNCOMMITTED -> null
}

private fun SavedReviewArchive.validatedForImport(): SavedReviewArchive {
    validate(this) {
        validate(SavedReviewArchive::originalReviewId).isNotBlank()
        validate(SavedReviewArchive::title).isNotBlank()
        validate(SavedReviewArchive::createdAt).isNotBlank()
        validate(SavedReviewArchive::updatedAt).isNotBlank()
    }
    comments.forEach { comment ->
        validate(comment) {
            validate(ReviewComment::id).isNotBlank()
            validate(ReviewComment::reviewId).isNotBlank()
            validate(ReviewComment::filePath).isNotBlank()
            validate(ReviewComment::body).isNotBlank()
            validate(ReviewComment::createdAt).isNotBlank()
            validate(ReviewComment::updatedAt).isNotBlank()
        }
    }
    when (targetType) {
        ReviewTargetType.COMMIT -> validate(this) {
            validate(SavedReviewArchive::beginCommit).isNotNull()
            validate(SavedReviewArchive::endCommit).isNotNull()
        }

        ReviewTargetType.COMMIT_RANGE -> validate(this) {
            validate(SavedReviewArchive::beginCommit).isNotNull()
            validate(SavedReviewArchive::endCommit).isNotNull()
        }

        ReviewTargetType.UNCOMMITTED -> throw MalformedImportedReviewException()
    }
    return this
}

private class MalformedImportedReviewException : RuntimeException()

internal fun String.toKebabCase(): String =
    trim()
        .lowercase()
        .replace("/", "-")
        .replace("\\", "-")
        .replace(Regex("\\s+"), "-")
