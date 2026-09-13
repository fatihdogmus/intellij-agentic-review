package dev.fatihdogmus.agenticreview.diff

import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestApplication
class DiffRequestBuilderIntegrationTest {
    private val project by projectFixture()

    @Test
    fun buildForFileStoresTitlesAndRequestMetadata() {
        val changedFile = ChangedFile(
            filePath = "src/Foo.kt",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.kt"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.kt"),
        )

        val request = DiffRequestBuilder(project).buildForFile("review-1", changedFile, "/tmp/repo")
        val data = request.getUserData(REVIEW_DIFF_REQUEST_DATA_KEY)

        assertThat(request.title).contains("Foo.kt")
        assertThat(data).isNotNull
        assertThat(data!!.reviewId).isEqualTo("review-1")
        assertThat(data.changedFile.filePath).isEqualTo("src/Foo.kt")
    }

    @Test
    fun buildForFileUsesMissingTitleWhenOneSideAbsent() {
        val changedFile = ChangedFile(
            filePath = "src/NewFile.kt",
            status = ChangedFileStatus.ADDED,
            beforeContent = null,
            afterContent = ReviewContent("after\n", "WORKTREE", "src/NewFile.kt"),
        )

        val request = DiffRequestBuilder(project).buildForFile("review-2", changedFile, "/tmp/repo")
        val data = request.getUserData(REVIEW_DIFF_REQUEST_DATA_KEY)

        assertThat(request.title).contains("NewFile.kt")
        assertThat(data).isNotNull
        assertThat(data!!.commentSide).isEqualTo(DiffSide.RIGHT)
    }

    @Test
    fun diffContentHasCorrectFileTypeForExistingFile(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        val sourceFile = Files.writeString(srcDir.resolve("Foo.java"), "class Foo {}")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile)

        val changedFile = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )

        val request =
            DiffRequestBuilder(project).buildForFile("review-1", changedFile, repoDir.toString()) as ContentDiffRequest
        val afterContent = request.contents[1] as? DocumentContent

        assertThat(afterContent).isNotNull
        assertThat(afterContent!!.contentType).isNotNull
    }

    @Test
    fun diffContentTypeIsCorrectForNonExistentFile() {
        val changedFile = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-1",
            changedFile,
            "/tmp/nonexistent-repo"
        ) as ContentDiffRequest
        val afterContent = request.contents[1] as? DocumentContent

        assertThat(afterContent).isNotNull
        assertThat(afterContent!!.contentType).isNotNull
    }

    @Test
    fun addedFileDiffContentHasNativeHighlighterForHistoricalText() {
        val changedFile = ChangedFile(
            filePath = "src/new-file.xml",
            status = ChangedFileStatus.ADDED,
            beforeContent = null,
            afterContent = ReviewContent("<root/>\n", "abc123", "src/new-file.xml"),
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-added",
            changedFile,
            "/tmp/nonexistent-repo"
        ) as ContentDiffRequest
        val afterContent = request.contents[1] as? DocumentContent
        val xmlFileType = FileTypeManager.getInstance().getFileTypeByFileName("new-file.xml") as LanguageFileType

        assertThat(afterContent).isNotNull
        assertThat(afterContent!!.contentType).isEqualTo(xmlFileType)
        assertThat(createHighlighter(afterContent)).isInstanceOfSatisfying(LexerEditorHighlighter::class.java) {
            assertThat(it.isPlain).isFalse()
        }
    }

    @Test
    fun renamedFileDiffContentsHaveNativeHighlightersForBothSides() {
        val changedFile = ChangedFile(
            filePath = "src/new/new-name.xml",
            status = ChangedFileStatus.RENAMED,
            beforeContent = ReviewContent("<old/>\n", "base", "src/old/old-name.xml"),
            afterContent = ReviewContent("<new/>\n", "head", "src/new/new-name.xml"),
            previousFilePath = "src/old/old-name.xml",
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-renamed",
            changedFile,
            "/tmp/nonexistent-repo"
        ) as ContentDiffRequest
        val beforeContent = request.contents[0] as? DocumentContent
        val afterContent = request.contents[1] as? DocumentContent
        val xmlFileType = FileTypeManager.getInstance().getFileTypeByFileName("new-name.xml") as LanguageFileType

        assertThat(beforeContent).isNotNull
        assertThat(afterContent).isNotNull
        assertThat(beforeContent!!.contentType).isEqualTo(xmlFileType)
        assertThat(afterContent!!.contentType).isEqualTo(xmlFileType)
        assertThat(createHighlighter(beforeContent)).isInstanceOfSatisfying(LexerEditorHighlighter::class.java) {
            assertThat(it.isPlain).isFalse()
        }
        assertThat(createHighlighter(afterContent)).isInstanceOfSatisfying(LexerEditorHighlighter::class.java) {
            assertThat(it.isPlain).isFalse()
        }
    }

    @Test
    fun addedFileDiffRequestCanBeShownInDiffPanel() {
        val changedFile = ChangedFile(
            filePath = "src/new-file.xml",
            status = ChangedFileStatus.ADDED,
            beforeContent = null,
            afterContent = ReviewContent("<root/>\n", "abc123", "src/new-file.xml"),
        )
        val request = DiffRequestBuilder(project).buildForFile("review-added", changedFile, "/tmp/nonexistent-repo")

        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                panel.setRequest(request)
                assertThat(panel.component).isNotNull
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    @Test
    fun renamedFileDiffRequestCanBeShownInDiffPanel() {
        val changedFile = ChangedFile(
            filePath = "src/new/new-name.xml",
            status = ChangedFileStatus.RENAMED,
            beforeContent = ReviewContent("<old/>\n", "base", "src/old/old-name.xml"),
            afterContent = ReviewContent("<new/>\n", "head", "src/new/new-name.xml"),
            previousFilePath = "src/old/old-name.xml",
        )
        val request = DiffRequestBuilder(project).buildForFile("review-renamed", changedFile, "/tmp/nonexistent-repo")

        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                panel.setRequest(request)
                assertThat(panel.component).isNotNull
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    @Test
    fun uncommittedModifiedDiffUsesLiveFileDocumentOnAfterSide(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        val sourceFile = Files.writeString(srcDir.resolve("Foo.java"), "after\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile)!!

        val changedFile = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-uncommitted",
            changedFile,
            repoDir.toString(),
            allowEditing = true,
        ) as ContentDiffRequest

        val afterContent = request.contents[1] as DocumentContent
        val liveDocument = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(virtualFile) }

        assertThat(liveDocument).isNotNull
        assertThat(afterContent.document).isSameAs(liveDocument)
    }

    @Test
    fun manualReviewDiffKeepsAfterSideDetachedFromLiveFile(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        val sourceFile = Files.writeString(srcDir.resolve("Foo.java"), "after\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile)!!

        val changedFile = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-manual",
            changedFile,
            repoDir.toString(),
            allowEditing = false,
        ) as ContentDiffRequest

        val afterContent = request.contents[1] as DocumentContent
        val liveDocument = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(virtualFile) }

        assertThat(liveDocument).isNotNull
        assertThat(afterContent.document).isNotSameAs(liveDocument)
    }

    @Test
    fun uncommittedAddedDiffUsesLiveFileDocument(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        val sourceFile = Files.writeString(srcDir.resolve("NewFile.java"), "after\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile)!!

        val changedFile = ChangedFile(
            filePath = "src/NewFile.java",
            status = ChangedFileStatus.ADDED,
            beforeContent = null,
            afterContent = ReviewContent("after\n", "WORKTREE", "src/NewFile.java"),
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-added-live",
            changedFile,
            repoDir.toString(),
            allowEditing = true,
        ) as ContentDiffRequest

        val afterContent = request.contents[1] as DocumentContent
        val liveDocument = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(virtualFile) }

        assertThat(liveDocument).isNotNull
        assertThat(afterContent.document).isSameAs(liveDocument)
    }

    @Test
    fun uncommittedRenamedDiffUsesLiveFileDocumentOnAfterSide(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val newDir = repoDir.resolve("src/new")
        Files.createDirectories(newDir)
        val sourceFile = Files.writeString(newDir.resolve("NewName.java"), "after\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile)!!

        val changedFile = ChangedFile(
            filePath = "src/new/NewName.java",
            status = ChangedFileStatus.RENAMED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/old/OldName.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/new/NewName.java"),
            previousFilePath = "src/old/OldName.java",
        )

        val request = DiffRequestBuilder(project).buildForFile(
            "review-renamed-live",
            changedFile,
            repoDir.toString(),
            allowEditing = true,
        ) as ContentDiffRequest

        val afterContent = request.contents[1] as DocumentContent
        val liveDocument = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(virtualFile) }

        assertThat(liveDocument).isNotNull
        assertThat(afterContent.document).isSameAs(liveDocument)
    }

    @Test
    fun viewerEditorsAreEditableOnlyForAllowedUncommittedSides(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("Foo.java"), "after\n")
        Files.writeString(srcDir.resolve("Added.java"), "added\n")
        Files.writeString(srcDir.resolve("NewName.java"), "renamed\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoDir)

        val modified = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )
        val added = ChangedFile(
            filePath = "src/Added.java",
            status = ChangedFileStatus.ADDED,
            beforeContent = null,
            afterContent = ReviewContent("added\n", "WORKTREE", "src/Added.java"),
        )
        val deleted = ChangedFile(
            filePath = "src/Deleted.java",
            status = ChangedFileStatus.DELETED,
            beforeContent = ReviewContent("gone\n", "HEAD", "src/Deleted.java"),
            afterContent = null,
        )
        val renamed = ChangedFile(
            filePath = "src/NewName.java",
            status = ChangedFileStatus.RENAMED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/OldName.java"),
            afterContent = ReviewContent("renamed\n", "WORKTREE", "src/NewName.java"),
            previousFilePath = "src/OldName.java",
        )

        val modifiedEditors = showRequestAndCaptureLiveDocumentStates(
            changedFile = modified,
            repositoryRoot = repoDir,
            request = DiffRequestBuilder(project).buildForFile("review-modified", modified, repoDir.toString(), allowEditing = true)
        )
        assertThat(modifiedEditors).hasSize(2)
        assertThat(modifiedEditors[0]).isFalse()
        assertThat(modifiedEditors[1]).isTrue()

        val addedEditors = showRequestAndCaptureLiveDocumentStates(
            changedFile = added,
            repositoryRoot = repoDir,
            request = DiffRequestBuilder(project).buildForFile("review-added", added, repoDir.toString(), allowEditing = true)
        )
        assertThat(addedEditors).hasSize(1)
        assertThat(addedEditors.single()).isTrue()

        val deletedEditors = showRequestAndCaptureLiveDocumentStates(
            changedFile = deleted,
            repositoryRoot = repoDir,
            request = DiffRequestBuilder(project).buildForFile("review-deleted", deleted, repoDir.toString(), allowEditing = true)
        )
        assertThat(deletedEditors).hasSize(1)
        assertThat(deletedEditors.single()).isFalse()

        val renamedEditors = showRequestAndCaptureLiveDocumentStates(
            changedFile = renamed,
            repositoryRoot = repoDir,
            request = DiffRequestBuilder(project).buildForFile("review-renamed", renamed, repoDir.toString(), allowEditing = true)
        )
        assertThat(renamedEditors).hasSize(2)
        assertThat(renamedEditors[0]).isFalse()
        assertThat(renamedEditors[1]).isTrue()

        val manualEditors = showRequestAndCaptureLiveDocumentStates(
            changedFile = modified,
            repositoryRoot = repoDir,
            request = DiffRequestBuilder(project).buildForFile("review-manual", modified, repoDir.toString(), allowEditing = false)
        )
        assertThat(manualEditors).hasSize(2)
        assertThat(manualEditors).containsExactly(false, false)
    }

    @Test
    fun reviewDiffEditorsDisableSpellchecking(@TempDir tempDir: Path) {
        val repoDir = tempDir.resolve("repo")
        val srcDir = repoDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("Foo.java"), "after\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoDir)

        val modified = ChangedFile(
            filePath = "src/Foo.java",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("before\n", "HEAD", "src/Foo.java"),
            afterContent = ReviewContent("after\n", "WORKTREE", "src/Foo.java"),
        )

        val spellcheckStates = showRequestAndCaptureSpellcheckStates(
            DiffRequestBuilder(project).buildForFile("review-modified", modified, repoDir.toString(), allowEditing = true)
        )

        assertThat(spellcheckStates).isNotEmpty
        assertThat(spellcheckStates).allMatch { it }
    }

    @Test
    fun onCommentInlaysReadyFiresAfterInlaysExistAndOnlyOnCommentEditor() {
        val manager = ReviewManagerService.getInstance(project)
        val review = Review(
            id = "review-order",
            title = "order",
            target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "abc"),
            repositoryRoot = "/tmp/repo",
            createdAt = "2026-09-12T10:00:00Z",
            updatedAt = "2026-09-12T10:00:00Z",
        )
        ReviewStateService.getInstance(project).addReview(review)
        val changedFile = ChangedFile(
            filePath = "src/Order.kt",
            status = ChangedFileStatus.MODIFIED,
            beforeContent = ReviewContent("a\nb\nc\n", "HEAD", "src/Order.kt"),
            afterContent = ReviewContent("a\nB\nc\n", "WORKTREE", "src/Order.kt"),
        )
        review.comments += ReviewComment(
            id = "c-order", reviewId = review.id, filePath = "src/Order.kt",
            anchor = CommentAnchor(newLine = 2), body = "here",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )

        val latch = CountDownLatch(1)
        var inlayCountAtCallback = -1
        var markerPresentOnCallbackEditor = false
        var markerPresentOnSibling = true
        var editorsSeen: List<Editor> = emptyList()

        val request = DiffRequestBuilder(project).buildForFile(
            review.id, changedFile, "/tmp/repo",
            onEditorsCreated = { editorsSeen = it },
            onCommentInlaysReady = { editor ->
                inlayCountAtCallback = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size
                markerPresentOnCallbackEditor = editor.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY) != null
                markerPresentOnSibling = editorsSeen.filter { it !== editor }
                    .any { it.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY) != null }
                latch.countDown()
            },
        )

        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                panel.setRequest(request)
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
            } finally {
                Disposer.dispose(disposable)
            }
        }

        assertThat(inlayCountAtCallback).isGreaterThanOrEqualTo(1)
        assertThat(markerPresentOnCallbackEditor).isTrue()
        assertThat(markerPresentOnSibling).isFalse()
    }

    private fun createHighlighter(content: DocumentContent) = runReadActionBlocking {
        DiffUtil.createEditorHighlighter(project, content)
    }

    private fun showRequestAndCaptureLiveDocumentStates(
        changedFile: ChangedFile,
        repositoryRoot: Path,
        request: com.intellij.diff.requests.DiffRequest,
    ): List<Boolean> {
        val latch = CountDownLatch(1)
        val liveDocumentStates = mutableListOf<Boolean>()
        val liveFile = changedFile.afterContent?.let {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repositoryRoot.resolve(changedFile.filePath))
        }
        val liveDocument = liveFile?.let { runReadActionBlocking { FileDocumentManager.getInstance().getDocument(it) } }

        request.getUserData(REVIEW_DIFF_REQUEST_DATA_KEY)?.let { requestData ->
            request.putUserData(REVIEW_DIFF_REQUEST_DATA_KEY, requestData.copy(onEditorsCreated = { editors ->
                liveDocumentStates.clear()
                liveDocumentStates.addAll(editors.map { (it as EditorEx).document == liveDocument })
                latch.countDown()
            }))
        }

        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                panel.setRequest(request)
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
            } finally {
                Disposer.dispose(disposable)
            }
        }

        return liveDocumentStates.toList()
    }

    private fun showRequestAndCaptureSpellcheckStates(request: com.intellij.diff.requests.DiffRequest): List<Boolean> {
        val latch = CountDownLatch(1)
        val spellcheckStates = mutableListOf<Boolean>()

        request.getUserData(REVIEW_DIFF_REQUEST_DATA_KEY)?.let { requestData ->
            request.putUserData(REVIEW_DIFF_REQUEST_DATA_KEY, requestData.copy(onEditorsCreated = { editors ->
                spellcheckStates.clear()
                spellcheckStates.addAll(editors.map { editor ->
                    val psiFile = runReadActionBlocking {
                        PsiDocumentManager.getInstance(project).getPsiFile((editor as EditorEx).document)
                    }
                    psiFile != null && isSpellCheckingDisabled(psiFile)
                })
                latch.countDown()
            }))
        }

        ApplicationManager.getApplication().invokeAndWait {
            val disposable = Disposer.newDisposable()
            try {
                val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
                panel.setRequest(request)
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
            } finally {
                Disposer.dispose(disposable)
            }
        }

        return spellcheckStates.toList()
    }

    private fun isSpellCheckingDisabled(psiFile: com.intellij.psi.PsiFile): Boolean = runCatching {
        val customizationClass = Class.forName("com.intellij.spellchecker.ui.SpellCheckingEditorCustomization")
        val method = customizationClass.getMethod("isSpellCheckingDisabled", com.intellij.psi.PsiFile::class.java)
        method.invoke(null, psiFile) as Boolean
    }.getOrDefault(false)
}
