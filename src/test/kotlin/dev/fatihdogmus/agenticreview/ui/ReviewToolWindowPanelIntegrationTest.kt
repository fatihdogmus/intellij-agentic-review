package dev.fatihdogmus.agenticreview.ui

import com.intellij.codeInsight.multiverse.EditorContextManager
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.diff.REVIEW_DIFF_COMMENT_EDITOR_KEY
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.persistence.ReviewStateService
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.testutil.configureGitMapping
import dev.fatihdogmus.agenticreview.testutil.gitHead
import dev.fatihdogmus.agenticreview.testutil.reviewSelector
import dev.fatihdogmus.agenticreview.testutil.runGit
import dev.fatihdogmus.agenticreview.testutil.turnCombo
import dev.fatihdogmus.agenticreview.testutil.write
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList

@TestApplication
class ReviewToolWindowPanelIntegrationTest {
    private val project by projectFixture()

    @BeforeEach
    fun setUp() {
        val manager = ReviewManagerService.getInstance(project)
        manager.hasUncommittedChangesSupplier = { false }
        manager.uncommittedChangesLoader = { emptyList() }
        manager.repositoryRootResolver = { project.basePath!! }
        manager.currentHeadHashSupplier = { "head-1" }
        TurnSnapshotService.getInstance(project).clearAll(notify = false)
    }

    @Test
    fun turnDropdownIsShownForUncommittedReviewAndHiddenForCommitReview() {
        val manager = ReviewManagerService.getInstance(project)
        manager.openDefaultReview()

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                assertThat(turnCombo(panel).isVisible).isTrue()

                val commitHash = initGitRepoWithCommit()

                val commitReview = Review(
                    id = "review-commit-ui",
                    title = "Commit review",
                    target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = commitHash),
                    repositoryRoot = project.basePath!!,
                    createdAt = "2026-05-07T14:20:00+03:00",
                    updatedAt = "2026-05-07T14:20:00+03:00",
                )
                ReviewStateService.getInstance(project).addReview(commitReview)
                manager.selectReview(commitReview.id)

                assertThat(turnCombo(panel).isVisible).isFalse()
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun completedTurnAppearsInTurnSelector() {
        val manager = ReviewManagerService.getInstance(project)
        manager.openDefaultReview()

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val turnService = TurnSnapshotService.getInstance(project)
                turnService.beginTurn("session-1", "step-1", project.basePath!!, "agent", "model")
                turnService.endTurn("session-1", "step-1", "completed", emptyList(), emptyList())

                val combo = turnCombo(panel)
                assertThat(combo.itemCount).isEqualTo(2)
                assertThat(combo.getItemAt(1).toString()).contains("agent")
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun reviewSelectorRendererShowsOpenAndResolvedCounts() {
        val review = Review(
            id = "review-renderer-ui",
            title = "Renderer review",
            target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "abc123"),
            repositoryRoot = project.basePath!!,
            createdAt = "2026-05-07T14:20:00+03:00",
            updatedAt = "2026-05-07T14:20:00+03:00",
            comments = mutableListOf(
                ReviewComment(
                    id = "c1",
                    reviewId = "review-renderer-ui",
                    filePath = "src/Foo.kt",
                    anchor = CommentAnchor(newLine = 1),
                    body = "open",
                    status = CommentStatus.OPEN,
                    createdAt = "2026-05-07T14:20:00+03:00",
                    updatedAt = "2026-05-07T14:20:00+03:00",
                ),
                ReviewComment(
                    id = "c2",
                    reviewId = "review-renderer-ui",
                    filePath = "src/Foo.kt",
                    anchor = CommentAnchor(newLine = 2),
                    body = "resolved",
                    status = CommentStatus.RESOLVED,
                    createdAt = "2026-05-07T14:20:00+03:00",
                    updatedAt = "2026-05-07T14:20:00+03:00",
                ),
            ),
        )
        ReviewStateService.getInstance(project).addReview(review)

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                @Suppress("UNCHECKED_CAST")
                val selector = reviewSelector(panel) as JComboBox<Review>
                val label =
                    selector.renderer.getListCellRendererComponent(JList<Review>(), review, 0, false, false) as JLabel

                assertThat(label.text).isEqualTo("Renderer review · 1 Open 1 Resolved")
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun focusTargetReturnsChangedFilesComponent() {
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                assertThat(panel.focusTarget).isNotNull
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun embeddedEditorContextsAreSeededBeforeDaemonNeedsThem() {
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            val virtualFile = LightVirtualFile("added-file.txt", PlainTextFileType.INSTANCE, "hello\n")
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            val document = psiFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            requireNotNull(psiFile)
            requireNotNull(document)

            val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, true)
            try {
                val contextManager = EditorContextManager.getInstance(project)
                assertThat(contextManager.getCachedEditorContexts(editor)).isNull()

                val method = ReviewToolWindowPanel::class.java.getDeclaredMethod("seedEmbeddedEditorContexts", List::class.java)
                method.isAccessible = true
                method.invoke(panel, listOf(editor))

                val cachedContexts = contextManager.getCachedEditorContexts(editor)
                assertThat(cachedContexts).isNotNull
                assertThat(cachedContexts!!.mainContext).isEqualTo(psiFile.codeInsightContext)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun embeddedEditorContextsAreSeededForLocalFileBackedEditors() {
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            val path = Path.of(project.basePath!!, "src", "LiveFile.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, "hello\n")

            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            val psiFile = virtualFile?.let { PsiManager.getInstance(project).findFile(it) }
            requireNotNull(virtualFile)
            requireNotNull(document)
            requireNotNull(psiFile)

            val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile, false)
            try {
                val contextManager = EditorContextManager.getInstance(project)
                assertThat(contextManager.getCachedEditorContexts(editor)).isNull()

                val method = ReviewToolWindowPanel::class.java.getDeclaredMethod("seedEmbeddedEditorContexts", List::class.java)
                method.isAccessible = true
                method.invoke(panel, listOf(editor))

                val cachedContexts = contextManager.getCachedEditorContexts(editor)
                assertThat(cachedContexts).isNotNull
                assertThat(cachedContexts!!.mainContext).isEqualTo(psiFile.codeInsightContext)
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
                Disposer.dispose(panel)
            }
        }
    }

    private fun initGitRepoWithCommit(): String =
        dev.fatihdogmus.agenticreview.testutil.initGitRepoWithCommit(Path.of(project.basePath!!))

    private data class NavFixture(
        val review: Review,
        val fooComment: ReviewComment,
        val barComment: ReviewComment,
        val goneComment: ReviewComment,
        val anchorlessComment: ReviewComment,
    )

    private fun seedTwoFileReview(): NavFixture {
        val manager = ReviewManagerService.getInstance(project)
        val repoRoot = Path.of(project.basePath!!)
        initGitRepoWithCommit()
        // Both files must exist in the FIRST commit. A file created only in the second commit is ADDED,
        // which renders as a one-sided viewer with a single editor — the two-sided test below then
        // cannot hold. Seeding them here makes the second commit a pure MODIFY.
        write(repoRoot.resolve("src/Bar.kt"), "bar seed\n")
        write(repoRoot.resolve("src/Gone.kt"), (1..200).joinToString("\n") { "gone line $it" } + "\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "seed both files")

        // Second commit: grow both files so each diff is tall enough that centring an anchor line
        // necessarily moves the viewport, and delete Gone.kt to give the LEFT-side test a target.
        write(repoRoot.resolve("src/Bar.kt"), (1..200).joinToString("\n") { "bar line $it" } + "\n")
        write(repoRoot.resolve("src/Foo.kt"), (1..200).joinToString("\n") { "foo line $it" } + "\n")
        runGit(repoRoot, "rm", "-q", "src/Gone.kt")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "second")
        val head = gitHead(repoRoot)
        // CommitChangesProvider resolves the repo via GitRepositoryResolver, which needs a VCS mapping.
        configureGitMapping(project, repoRoot)

        // Construct the Review directly (as turnDropdownIsShownForUncommittedReview... does) rather
        // than via createCommitReview, so the fixture does not depend on openReview side effects.
        val review = Review(
            id = "review-nav",
            title = "nav",
            target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = head),
            repositoryRoot = project.basePath!!,
            createdAt = "2026-09-12T10:00:00Z",
            updatedAt = "2026-09-12T10:00:00Z",
        )
        ReviewStateService.getInstance(project).addReview(review)
        val foo = ReviewComment(
            id = "nav-foo", reviewId = review.id, filePath = "src/Foo.kt",
            anchor = CommentAnchor(newLine = 150), body = "foo comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        val barC = ReviewComment(
            id = "nav-bar", reviewId = review.id, filePath = "src/Bar.kt",
            anchor = CommentAnchor(newLine = 150), body = "bar comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        val gone = ReviewComment(
            id = "nav-gone", reviewId = review.id, filePath = "src/Gone.kt",
            // Deleted file: afterContent is null, so DiffRequestBuilder resolves commentSide to LEFT.
            anchor = CommentAnchor(oldLine = 150), body = "gone comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        val anchorless = ReviewComment(
            id = "nav-anchorless", reviewId = review.id, filePath = "src/Bar.kt",
            anchor = CommentAnchor(), body = "anchorless comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        review.comments += listOf(foo, barC, gone, anchorless)
        manager.selectReview(review.id)
        return NavFixture(review, foo, barC, gone, anchorless)
    }

    private fun changedFilesPanel(panel: ReviewToolWindowPanel): ChangedFilesPanel {
        val field = ReviewToolWindowPanel::class.java.getDeclaredField("changedFilesPanel")
        field.isAccessible = true
        return field.get(panel) as ChangedFilesPanel
    }

    private fun pendingNavigation(panel: ReviewToolWindowPanel): Any? {
        val field = ReviewToolWindowPanel::class.java.getDeclaredField("pendingCommentNavigation")
        field.isAccessible = true
        return field.get(panel)
    }

    private fun commentEditor(panel: ReviewToolWindowPanel): EditorEx? =
        panel.embeddedEditors.filterIsInstance<EditorEx>().firstOrNull { it.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY) != null }

    /**
     * Called ON the EDT (inside invokeAndWait). Viewer creation is posted as invokeLater events, so
     * pumping the queue is what lets it complete. Never sleep here — that would block the EDT itself.
     */
    private fun awaitCommentEditorFor(panel: ReviewToolWindowPanel, filePath: String): EditorEx {
        repeat(200) {
            val editor = commentEditor(panel)
            if (editor?.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY)?.changedFile?.filePath == filePath) return editor
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        }
        error("No comment editor for $filePath after pumping the EDT 200 times")
    }

    /**
     * Diff-viewer creation is synchronous in this headless test harness (confirmed empirically:
     * even the first-ever viewer resolves after zero EDT pumps), so a real selectCommentRow() call
     * for an undisplayed file has its viewer already built — and its pending navigation already
     * consumed by the fallback path — before onCommentSelected's handler returns. That makes it
     * impossible to observe a genuinely still-pending record via the normal click path. This helper
     * injects the private field directly so tests of downstream clearing (turn switch, review switch)
     * can establish a real, non-vacuous precondition.
     */
    private fun setPendingNavigation(panel: ReviewToolWindowPanel, reviewId: String, commentId: String, filePath: String) {
        val pendingClass = ReviewToolWindowPanel::class.java.declaredClasses.first { it.simpleName == "PendingCommentNavigation" }
        val ctor = pendingClass.getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
        ctor.isAccessible = true
        val instance = ctor.newInstance(reviewId, commentId, filePath)
        val field = ReviewToolWindowPanel::class.java.getDeclaredField("pendingCommentNavigation")
        field.isAccessible = true
        field.set(panel, instance)
    }

    /** Asserts [line] (0-based) lies inside the editor's visible viewport — i.e. scrollTo centred it. */
    private fun assertAnchorVisible(editor: EditorEx, line: Int) {
        val visible = editor.scrollingModel.visibleArea
        val anchorY = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
        assertThat(anchorY).isBetween(visible.y, visible.y + visible.height)
    }

    private fun selectCommentRow(files: ChangedFilesPanel, bodyText: String) {
        val tree = dev.fatihdogmus.agenticreview.testutil.reviewTree(files)
        val row = (0 until tree.rowCount).first { r ->
            val p = tree.getPathForRow(r)
            tree.cellRenderer.getTreeCellRendererComponent(tree, p.lastPathComponent, false, true, true, r, false)
                .toString().contains(bodyText)
        }
        tree.setSelectionRow(row)
    }

    @Test
    fun selectingCommentForDisplayedFileScrollsItsCommentEditor() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                // Bar.kt sorts first and is auto-selected; navigate within it.
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                val before = editor.scrollingModel.verticalScrollOffset

                selectCommentRow(files, "bar comment")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(pendingNavigation(panel)).isNull()
                assertThat(editor.scrollingModel.verticalScrollOffset).isNotEqualTo(before)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun selectingCommentForDifferentFileScrollsOnceViewerIsBuilt() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                awaitCommentEditorFor(panel, "src/Bar.kt")

                selectCommentRow(files, "foo comment")
                val fooEditor = awaitCommentEditorFor(panel, "src/Foo.kt")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(pendingNavigation(panel)).isNull()
                assertAnchorVisible(fooEditor, fixture.fooComment.anchor.newLine!! - 1)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun twoSidedDiffScrollsTheRightSideCommentEditor() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                assertThat(panel.embeddedEditors).hasSize(2)

                // The marked editor must be the RIGHT side for a modified file. Do NOT assert the
                // sibling's offset is unchanged: TextDiffSettings enables synchronised scrolling, so the
                // sibling legitimately follows and such an assertion proves nothing about which editor
                // received scrollTo. The marker key is the authoritative signal (Task 7 proves exactly
                // one editor carries it).
                assertThat(editor.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY)!!.commentSide)
                    .isEqualTo(DiffSide.RIGHT)
                assertThat(editor).isSameAs(panel.embeddedEditors[1])

                selectCommentRow(files, "bar comment")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertAnchorVisible(editor, fixture.barComment.anchor.newLine!! - 1)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun deletedFileScrollsTheLeftSideCommentEditor() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                awaitCommentEditorFor(panel, "src/Bar.kt")

                selectCommentRow(files, "gone comment")
                val goneEditor = awaitCommentEditorFor(panel, "src/Gone.kt")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(goneEditor.getUserData(REVIEW_DIFF_COMMENT_EDITOR_KEY)!!.commentSide)
                    .isEqualTo(DiffSide.LEFT)
                assertThat(pendingNavigation(panel)).isNull()
                assertAnchorVisible(goneEditor, fixture.goneComment.anchor.oldLine!! - 1)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun anchorlessCommentDoesNotScrollAndClearsPendingNavigation() {
        seedTwoFileReview() // fixture fields unused here; the comment is located by body text
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                // Scroll away from the top first: at offset 0, "did not scroll" and "scrolled to
                // logical line 0" are indistinguishable, which would make this assertion vacuous.
                editor.scrollingModel.scrollTo(LogicalPosition(150, 0), ScrollType.CENTER)
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                val before = editor.scrollingModel.verticalScrollOffset
                assertThat(before).isNotEqualTo(0)

                selectCommentRow(files, "anchorless comment")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(editor.scrollingModel.verticalScrollOffset).isEqualTo(before)
                // Consumed, not left dangling: a later unrelated viewer must not pick it up.
                assertThat(pendingNavigation(panel)).isNull()
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun switchingToTurnModeClearsPendingNavigation() {
        seedTwoFileReview()
        val turnService = TurnSnapshotService.getInstance(project)
        val repoRoot = Path.of(project.basePath!!)
        // TurnSnapshotService's diff is git-HEAD-vs-working-tree (unlike the review's own
        // git-history-only diff), so Foo.kt's on-disk content must actually differ from HEAD here or
        // buildChangedFile treats it as unchanged and silently drops it, leaving the turn's file set
        // empty regardless of tree selection below.
        write(repoRoot.resolve("src/Foo.kt"), (1..200).joinToString("\n") { "foo line $it" } + "\nturn edit\n")
        turnService.beginTurn("s-nav", "t-nav", project.basePath!!, null, null)
        turnService.endTurn("s-nav", "t-nav", "completed", listOf(repoRoot.resolve("src/Foo.kt").toString()), emptyList())

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                awaitCommentEditorFor(panel, "src/Bar.kt")

                // Move the tree's real selection onto Foo.kt's file row (Foo.kt is the turn's only
                // changed file) *before* injecting the pending record. This is what gives the test
                // teeth: turnCombo's own action listener runs ChangedFilesPanel.refreshModel first
                // (autoSelectFirst) and only *that* call re-fires onSelectionChanged — which would
                // also null this field, at :114 — when the effective selection actually changes
                // (refreshModel compares previous vs. new selected path at :301/:318). With the tree
                // already on Foo.kt, the turn-filtered file set still contains Foo.kt at that same
                // path, so refreshModel leaves the selection untouched and does not re-fire
                // onSelectionChanged; onTurnChanged's own `pendingCommentNavigation = null` (:125) is
                // therefore the sole thing that can clear the field below. A comment row cannot be used
                // here instead: selecting one would route through onCommentSelected's fallback path,
                // which would consume the record before the turn switch even runs (see
                // setPendingNavigation's doc). Selecting a different file (e.g. leaving Bar.kt
                // selected) would instead make refreshModel's auto-select genuinely change the
                // selection to Foo.kt, firing onSelectionChanged for an unrelated reason and clearing
                // the field before onTurnChanged runs — exactly the vacuity this setup avoids.
                val tree = dev.fatihdogmus.agenticreview.testutil.reviewTree(files)
                val fooFileRow = (0 until tree.rowCount).first { r ->
                    tree.getPathForRow(r).lastPathComponent.toString().contains("Foo.kt")
                }
                tree.setSelectionRow(fooFileRow)

                // Inject the pending record only after the selection above, not before: selecting
                // Foo.kt's file row itself fires onSelectionChanged, which would immediately null a
                // record injected earlier. Injected directly rather than via selectCommentRow (see
                // setPendingNavigation's doc) for the fallback-path reason noted above.
                val reviewId = requireNotNull(ReviewManagerService.getInstance(project).currentReviewId)
                setPendingNavigation(panel, reviewId, "nav-foo", "src/Foo.kt")
                assertThat(pendingNavigation(panel)).isNotNull()

                files.setTurnsEnabled(true)
                files.refreshTurns(turnService)
                turnCombo(panel).selectedIndex = 1
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(pendingNavigation(panel)).isNull()
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun plainFileClickClearsPendingNavigation() {
        seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                awaitCommentEditorFor(panel, "src/Bar.kt")
                selectCommentRow(files, "foo comment")
                // Immediately select a plain file row before pumping the EDT.
                // Note: diff-viewer creation is synchronous in this headless harness (see
                // setPendingNavigation's doc), so selectCommentRow above already resolves the pending
                // record via the fallback path before this line runs. The setSelectionRow call below
                // does still fire the tree's real selection listener (selection genuinely moves from
                // the "foo comment" node to Bar.kt's file row, which is not the same node), so it does
                // reach onSelectionChanged's `pendingCommentNavigation = null` (:114) — but the field is
                // already null by then from the earlier fallback-path consumption, so this assertion
                // does not independently exercise that clearing line in this environment. Left as
                // specified since it is still a correct, meaningful regression check overall.
                val tree = dev.fatihdogmus.agenticreview.testutil.reviewTree(files)
                val barRow = (0 until tree.rowCount).first { r ->
                    tree.getPathForRow(r).lastPathComponent.toString().contains("Bar.kt")
                }
                tree.setSelectionRow(barRow)

                assertThat(pendingNavigation(panel)).isNull()
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun switchingReviewClearsPendingNavigation() {
        val fixture = seedTwoFileReview()
        val manager = ReviewManagerService.getInstance(project)
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                awaitCommentEditorFor(panel, "src/Bar.kt")
                // Established directly rather than via selectCommentRow: see setPendingNavigation's doc
                // — the fallback path would otherwise already consume the record in the same call stack.
                setPendingNavigation(panel, fixture.review.id, "nav-foo", "src/Foo.kt")
                assertThat(pendingNavigation(panel)).isNotNull()

                manager.openDefaultReview()

                assertThat(pendingNavigation(panel)).isNull()
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun matchesRequiresBothReviewIdAndFilePath() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                // Bar.kt is displayed (auto-selected, sorts first) and its editor is embedded.
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                val before = editor.scrollingModel.verticalScrollOffset

                // Pending record is for the SAME review but a DIFFERENT file (Foo.kt) than the one
                // currently displayed. Only the filePath half of `matches` stands between this and the
                // displayed Bar.kt editor being (wrongly) treated as a match.
                setPendingNavigation(panel, fixture.review.id, fixture.fooComment.id, "src/Foo.kt")

                val method = ReviewToolWindowPanel::class.java.getDeclaredMethod("scrollDisplayedEditorToPendingComment")
                method.isAccessible = true
                method.invoke(panel)

                // Not consumed (no match found) and the displayed editor was left alone.
                assertThat(pendingNavigation(panel)).isNotNull()
                assertThat(editor.scrollingModel.verticalScrollOffset).isEqualTo(before)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun commentAnchorPastEndOfDocumentClampsToLastLine() {
        val fixture = seedTwoFileReview()
        val outOfRange = ReviewComment(
            id = "nav-oob", reviewId = fixture.review.id, filePath = "src/Bar.kt",
            anchor = CommentAnchor(newLine = 10_000), body = "out of range comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        fixture.review.comments += outOfRange

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")

                selectCommentRow(files, "out of range comment")
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

                assertThat(pendingNavigation(panel)).isNull()
                assertAnchorVisible(editor, editor.document.lineCount - 1)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun reviewIdMismatchGuardBlocksScrollingToACommentFromAnotherReview() {
        val fixture = seedTwoFileReview()
        val otherReview = Review(
            id = "review-other",
            title = "other",
            target = ReviewTarget(type = ReviewTargetType.COMMIT, commitHash = "head-1"),
            repositoryRoot = project.basePath!!,
            createdAt = "2026-09-12T10:00:00Z",
            updatedAt = "2026-09-12T10:00:00Z",
        )
        val otherComment = ReviewComment(
            id = "other-review-comment", reviewId = otherReview.id, filePath = "src/Bar.kt",
            anchor = CommentAnchor(newLine = 190), body = "other review comment",
            createdAt = "2026-09-12T10:00:00Z", updatedAt = "2026-09-12T10:00:00Z",
        )
        otherReview.comments += otherComment
        ReviewStateService.getInstance(project).addReview(otherReview)

        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                // fixture.review (not otherReview) remains the current review throughout.
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                val before = editor.scrollingModel.verticalScrollOffset

                // A pending record referencing otherReview's comment. findCommentWithReview searches
                // ACROSS reviews, so it would resolve otherComment if this guard did not exist first.
                setPendingNavigation(panel, otherReview.id, otherComment.id, "src/Bar.kt")

                val method = ReviewToolWindowPanel::class.java.getDeclaredMethod(
                    "consumePendingNavigation", com.intellij.openapi.editor.ex.EditorEx::class.java,
                )
                method.isAccessible = true
                method.invoke(panel, editor)

                assertThat(pendingNavigation(panel)).isNull()
                assertThat(editor.scrollingModel.verticalScrollOffset).isEqualTo(before)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun navigatingToADeletedCommentDoesNotThrowAndClearsThePendingRecord() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val editor = awaitCommentEditorFor(panel, "src/Bar.kt")
                val before = editor.scrollingModel.verticalScrollOffset

                // Same review and file as the displayed editor, but a comment id that no longer exists —
                // as if the comment was deleted between the click and the navigation being consumed.
                setPendingNavigation(panel, fixture.review.id, "comment-id-that-was-deleted", "src/Bar.kt")

                val method = ReviewToolWindowPanel::class.java.getDeclaredMethod("scrollDisplayedEditorToPendingComment")
                method.isAccessible = true
                method.invoke(panel)

                assertThat(pendingNavigation(panel)).isNull()
                assertThat(editor.scrollingModel.verticalScrollOffset).isEqualTo(before)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    @Test
    fun disposeClearsPendingNavigation() {
        val fixture = seedTwoFileReview()
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            awaitCommentEditorFor(panel, "src/Bar.kt")
            setPendingNavigation(panel, fixture.review.id, fixture.fooComment.id, "src/Foo.kt")
            assertThat(pendingNavigation(panel)).isNotNull()

            Disposer.dispose(panel)

            assertThat(pendingNavigation(panel)).isNull()
        }
    }

    @Test
    fun commentRowsSurviveLoadMarkSeenAndMutationRefreshes() {
        val fixture = seedTwoFileReview()
        val manager = ReviewManagerService.getInstance(project)
        ApplicationManager.getApplication().invokeAndWait {
            val panel = ReviewToolWindowPanel(project)
            try {
                val files = changedFilesPanel(panel)
                val tree = dev.fatihdogmus.agenticreview.testutil.reviewTree(files)
                fun rowTexts() = (0 until tree.rowCount).map { r ->
                    val p = tree.getPathForRow(r)
                    tree.cellRenderer.getTreeCellRendererComponent(tree, p.lastPathComponent, false, true, true, r, false).toString()
                }

                // after load (loadChangedFilesIfNeeded's onSuccess)
                assertThat(rowTexts()).anyMatch { it.contains("foo comment") }

                // after markFileSeen rebuild (refreshDiff) — selecting Foo.kt flips it to seen
                val fooRow = (0 until tree.rowCount).first { r -> tree.getPathForRow(r).lastPathComponent.toString().contains("Foo.kt") }
                tree.setSelectionRow(fooRow)
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                assertThat(rowTexts()).anyMatch { it.contains("foo comment") }

                // after a model mutation (refreshUi via stateListener)
                manager.markCommentResolved(fixture.barComment.id)
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                assertThat(rowTexts()).anyMatch { it.contains("foo comment") }
                assertThat(rowTexts()).anyMatch { it.contains("bar comment") }
            } finally {
                Disposer.dispose(panel)
            }
        }
    }
}
