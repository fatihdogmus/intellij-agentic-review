package dev.fatihdogmus.agenticreview.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import dev.fatihdogmus.agenticreview.model.DiffSide
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import java.nio.file.Path

class DiffRequestBuilder(private val project: Project) {
    fun buildForFile(
        reviewId: String,
        changedFile: ChangedFile,
        repositoryRoot: String,
        allowEditing: Boolean = false,
        onEditorsCreated: ((List<Editor>) -> Unit)? = null,
        onCommentInlaysReady: ((EditorEx) -> Unit)? = null,
    ): DiffRequest {
        val beforeContent = createContent(
            changedFile.beforeContent,
            repositoryRoot,
            changedFile.previousFilePath ?: changedFile.filePath,
        )
        val afterContent = createContent(
            changedFile.afterContent,
            repositoryRoot,
            changedFile.filePath,
            preferFileDocument = changedFile.shouldUseEditableAfterContent(allowEditing),
        )

        val beforeTitle = changedFile.beforeContent?.let { "${it.revisionTitle}  ${it.filePath}" }
            ?: "Missing  ${changedFile.filePath}"
        val afterTitle = changedFile.afterContent?.let { "${it.revisionTitle}  ${it.filePath}" }
            ?: "Missing  ${changedFile.filePath}"

        val request = SimpleDiffRequest(changedFile.filePath, beforeContent, afterContent, beforeTitle, afterTitle)

        val beforeIsWorktree = changedFile.beforeContent?.isWorktreeRevision() == true
        val afterIsWorktree = changedFile.afterContent?.isWorktreeRevision() == true
        if (!beforeIsWorktree && afterIsWorktree) request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.RIGHT)
        if (beforeIsWorktree && !afterIsWorktree) request.putUserData(DiffUserDataKeys.MASTER_SIDE, Side.LEFT)

        return request.apply {
            putUserData(
                REVIEW_DIFF_REQUEST_DATA_KEY,
                ReviewDiffRequestData(
                    reviewId = reviewId,
                    changedFile = changedFile,
                    commentSide = if (changedFile.afterContent != null) DiffSide.RIGHT else DiffSide.LEFT,
                    onEditorsCreated = onEditorsCreated,
                    onCommentInlaysReady = onCommentInlaysReady,
                ),
            )
        }
    }

    private fun createContent(
        revision: ReviewContent?,
        repositoryRoot: String,
        fallbackPath: String,
        preferFileDocument: Boolean = false,
    ): DiffContent {
        if (revision == null) return DiffContentFactory.getInstance().createEmpty()
        val factory = DiffContentFactory.getInstance()
        val absolutePath = Path.of(repositoryRoot, revision.filePath.ifBlank { fallbackPath })

        if (preferFileDocument) {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(absolutePath)
            if (virtualFile != null) {
                factory.createDocument(project, virtualFile)?.let { return it }
            }
        }

        val filePath = LocalFilePath(absolutePath.toString(), false)

        return factory.create(project, revision.text, filePath)
    }

    private fun ReviewContent.isWorktreeRevision(): Boolean = revisionTitle.isBlank() || revisionTitle == "WORKTREE"

    private fun ChangedFile.shouldUseEditableAfterContent(allowEditing: Boolean): Boolean {
        if (!allowEditing || afterContent == null) return false
        return status == ChangedFileStatus.ADDED ||
            status == ChangedFileStatus.MODIFIED ||
            status == ChangedFileStatus.RENAMED
    }
}
