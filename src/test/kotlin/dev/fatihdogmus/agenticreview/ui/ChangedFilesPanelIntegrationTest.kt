package dev.fatihdogmus.agenticreview.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.fatihdogmus.agenticreview.model.*
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.testutil.findComponents
import dev.fatihdogmus.agenticreview.testutil.reviewTree
import dev.fatihdogmus.agenticreview.testutil.runGit
import dev.fatihdogmus.agenticreview.testutil.titleLabel
import dev.fatihdogmus.agenticreview.testutil.turnCombo
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.ReviewContent
import dev.fatihdogmus.agenticreview.vcs.seenKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import com.intellij.openapi.actionSystem.impl.ActionButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTree

@TestApplication
class ChangedFilesPanelIntegrationTest {
    private val project by projectFixture()

    @BeforeEach
    fun resetTurns() {
        TurnSnapshotService.getInstance(project).clearAll(notify = false)
        PropertiesComponent.getInstance(project).setValue(HIDE_FILES_WITHOUT_COMMENTS_KEY, false)
        PropertiesComponent.getInstance(project).setValue(HIDE_RESOLVED_COMMENTS_KEY, false)
    }

    /** Resolves the [ToggleAction] backing a filter menu entry, driven exactly as a real menu click would. */
    private fun filterAction(panel: ChangedFilesPanel, text: String): ToggleAction =
        panel.filterActionGroupForTest().getChildren(null).filterIsInstance<ToggleAction>()
            .single { it.templatePresentation.text == text }

    private fun toggleFilter(panel: ChangedFilesPanel, text: String) {
        val action = filterAction(panel, text)
        val event = TestActionEvent.createTestEvent(action)
        action.setSelected(event, !action.isSelected(event))
    }

    private fun isFilterSelected(panel: ChangedFilesPanel, text: String): Boolean {
        val action = filterAction(panel, text)
        return action.isSelected(TestActionEvent.createTestEvent(action))
    }

    /** The header JPanel the panel places at BorderLayout.NORTH, holding the caption and kebab menu. */
    private fun headerComponent(panel: ChangedFilesPanel): JComponent {
        val layout = panel.component.layout as BorderLayout
        return layout.getLayoutComponent(panel.component, BorderLayout.NORTH) as JComponent
    }

    @Test
    fun setReviewFilesSelectsFirstFileAndTracksCurrentFiles() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val files = listOf(sampleChangedFile("src/Foo.kt"), sampleChangedFile("src/Bar.kt"))

            panel.setReviewFiles(files, selectedFilePath = null, seenFileKeys = emptySet(), comments = emptyList())

            assertThat(panel.currentFiles()).containsExactlyElementsOf(files)
            assertThat(panel.selectedFile()?.filePath).isEqualTo("src/Foo.kt")
        }
    }

    @Test
    fun treeRendererMarksUnseenFilesWithAsterisk() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val unseen = sampleChangedFile("src/Foo.kt")
            val seen = sampleChangedFile("src/Bar.kt")
            panel.setReviewFiles(listOf(unseen, seen), selectedFilePath = null, seenFileKeys = setOf(seen.seenKey()), comments = emptyList())

            val rowTexts = treeRowTexts(reviewTree(panel))

            assertThat(rowTexts).anyMatch { it.contains("* Foo.kt") }
            assertThat(rowTexts).anyMatch { it.contains("Bar.kt") && !it.contains("* Bar.kt") }
        }
    }

    @Test
    fun treeRendererShowsCompactRenameStatusAndLineStats() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val renamed = ChangedFile(
                filePath = "src/new/Foo.kt",
                status = ChangedFileStatus.RENAMED,
                beforeContent = ReviewContent("one\ntwo", "before", "src/old/Foo.kt"),
                afterContent = ReviewContent("one\nthree\nfour", "after", "src/new/Foo.kt"),
                previousFilePath = "src/old/Foo.kt",
            )
            panel.setReviewFiles(listOf(renamed), selectedFilePath = null, seenFileKeys = emptySet(), comments = emptyList())

            val rowTexts = treeRowTexts(reviewTree(panel))
            val fileRow = rowTexts.single { it.contains("Foo.kt") }

            assertThat(fileRow).contains("* Foo.kt")
            assertThat(fileRow).contains("R")
            assertThat(fileRow).contains("+2")
            assertThat(fileRow).contains("-1")
            assertThat(fileRow).contains("from Foo.kt")
            assertThat(fileRow).doesNotContain("src/old/Foo.kt")
            assertThat(fileRow).doesNotContain("src/new/Foo.kt")
        }
    }

    @Test
    fun treeRendererShowsAccurateStatsForDisjointEdits() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val modified = ChangedFile(
                filePath = "src/Foo.kt",
                status = ChangedFileStatus.MODIFIED,
                beforeContent = ReviewContent(
                    "one\nshared\ntwo\nshared-again\nthree\n",
                    "before",
                    "src/Foo.kt",
                ),
                afterContent = ReviewContent(
                    "ONE\nshared\ntwo\nshared-again\nTHREE\n",
                    "after",
                    "src/Foo.kt",
                ),
            )
            panel.setReviewFiles(listOf(modified), selectedFilePath = null, seenFileKeys = emptySet(), comments = emptyList())

            val rowTexts = treeRowTexts(reviewTree(panel))
            val fileRow = rowTexts.single { it.contains("Foo.kt") }

            assertThat(fileRow).contains("+2")
            assertThat(fileRow).contains("-2")
        }
    }

    @Test
    fun treeCompactsSingleChildDirectoryChains() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            panel.setReviewFiles(
                listOf(
                    sampleChangedFile("src/main/kotlin/dev/fatihdogmus/Foo.kt"),
                    sampleChangedFile("src/main/kotlin/dev/fatihdogmus/Bar.kt"),
                ),
                selectedFilePath = null,
                seenFileKeys = emptySet(),
                comments = emptyList(),
            )

            val rowTexts = treeRowTexts(reviewTree(panel))

            assertThat(rowTexts.first()).isEqualTo("src/main/kotlin/dev/fatihdogmus")
            assertThat(rowTexts).doesNotContain("src", "main", "kotlin", "dev")
        }
    }

    @Test
    fun turnDropdownIsHiddenWhenTurnsDisabledAndVisibleWhenEnabled() {
        val turnService = TurnSnapshotService.getInstance(project)
        val repoRoot = Path.of(project.basePath!!)
        val file = repoRoot.resolve("src/Foo.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "after\n")

        turnService.beginTurn("session-ui", "step-ui", project.basePath!!, null, null)
        turnService.endTurn("session-ui", "step-ui", "completed", listOf(file.toString()), emptyList())

        onEdt {
            val panel = ChangedFilesPanel(project)
            panel.setTurnsEnabled(false)
            panel.refreshTurns(turnService)
            assertThat(turnCombo(panel).isVisible).isFalse()

            panel.setTurnsEnabled(true)
            panel.refreshTurns(turnService)
            assertThat(turnCombo(panel).isVisible).isTrue()
            assertThat(turnCombo(panel).itemCount).isEqualTo(2)
        }
    }

    @Test
    fun selectingTurnSwitchesToTurnChangedFilesMode() {
        val turnService = TurnSnapshotService.getInstance(project)
        val repoRoot = Path.of(project.basePath!!)
        val file = repoRoot.resolve("src/Foo.kt")

        runGit(repoRoot, "init")
        runGit(repoRoot, "config", "user.email", "test@example.com")
        runGit(repoRoot, "config", "user.name", "Test User")
        Files.createDirectories(file.parent)
        Files.writeString(file, "before\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "initial")
        Files.writeString(file, "after\n")

        turnService.beginTurn("session-mode", "step-mode", project.basePath!!, null, null)
        turnService.endTurn("session-mode", "step-mode", "completed", listOf(file.toString()), emptyList())

        onEdt {
            val panel = ChangedFilesPanel(project)
            panel.setTurnsEnabled(true)
            panel.refreshTurns(turnService)

            turnCombo(panel).selectedIndex = 1

            assertThat(panel.currentFiles()).hasSize(1)
            assertThat(panel.currentFiles().single().filePath).isEqualTo("src/Foo.kt")
            assertThat(titleLabel(panel).text).isEqualTo("Turn Changed Files")
        }
    }

    private fun sampleChangedFile(path: String): ChangedFile = ChangedFile(
        filePath = path,
        status = ChangedFileStatus.MODIFIED,
        beforeContent = ReviewContent("before\n", "before", path),
        afterContent = ReviewContent("after\n", "after", path),
    )

    private fun sampleComment(
        path: String,
        body: String = "A comment",
        status: CommentStatus = CommentStatus.OPEN,
        newLine: Int? = 3,
        replies: List<CommentReply> = emptyList(),
    ): ReviewComment = ReviewComment(
        id = UUID.randomUUID().toString(),
        reviewId = "review-1",
        filePath = path,
        anchor = CommentAnchor(newLine = newLine),
        body = body,
        status = status,
        createdAt = "2026-09-12T10:00:00Z",
        updatedAt = "2026-09-12T10:00:00Z",
        replies = replies.toMutableList(),
    )

    @Test
    fun hideFilesWithoutCommentsDropsCommentlessFilesAndPrunesEmptyDirectories() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val withComment = sampleChangedFile("src/a/Foo.kt")
            val without = sampleChangedFile("src/b/Bar.kt")
            panel.setReviewFiles(listOf(withComment, without), null, emptySet(), listOf(sampleComment("src/a/Foo.kt")))

            toggleFilter(panel, "Hide files without comments")

            val rows = treeRowTexts(reviewTree(panel))
            assertThat(rows).anyMatch { it.contains("Foo.kt") }
            assertThat(rows).noneMatch { it.contains("Bar.kt") }
            assertThat(rows).noneMatch { it == "b" || it == "src/b" }
        }
    }

    @Test
    fun hideResolvedDropsResolvedRowsAndCombinesWithHideFilesWithoutComments() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val open = sampleComment("src/Foo.kt", body = "open", newLine = 1)
            val resolved = sampleComment("src/Foo.kt", body = "resolved", newLine = 2, status = CommentStatus.RESOLVED)
            val allResolvedFile = sampleChangedFile("src/Bar.kt")
            val resolvedOnly = sampleComment("src/Bar.kt", body = "closed", status = CommentStatus.RESOLVED)
            panel.setReviewFiles(listOf(file, allResolvedFile), null, emptySet(), listOf(open, resolved, resolvedOnly))

            toggleFilter(panel, "Hide resolved comments")
            var rows = treeRowTexts(reviewTree(panel))
            assertThat(rows).anyMatch { it.contains("open") }
            assertThat(rows).noneMatch { it.contains("resolved") || it.contains("closed") }
            assertThat(rows).anyMatch { it.contains("Bar.kt") }

            toggleFilter(panel, "Hide files without comments")
            rows = treeRowTexts(reviewTree(panel))
            assertThat(rows).noneMatch { it.contains("Bar.kt") }
        }
    }

    @Test
    fun bothFilterValuesRoundTripThroughPropertiesComponentAndDefaultToFalse() {
        onEdt {
            val properties = PropertiesComponent.getInstance(project)
            val first = ChangedFilesPanel(project)
            assertThat(isFilterSelected(first, "Hide files without comments")).isFalse()
            assertThat(isFilterSelected(first, "Hide resolved comments")).isFalse()

            toggleFilter(first, "Hide files without comments")
            toggleFilter(first, "Hide resolved comments")
            assertThat(properties.getBoolean(HIDE_FILES_WITHOUT_COMMENTS_KEY, false)).isTrue()
            assertThat(properties.getBoolean(HIDE_RESOLVED_COMMENTS_KEY, false)).isTrue()

            val second = ChangedFilesPanel(project)
            assertThat(isFilterSelected(second, "Hide files without comments")).isTrue()
            assertThat(isFilterSelected(second, "Hide resolved comments")).isTrue()
        }
    }

    @Test
    fun emptyTextDistinguishesFilteredFromGenuinelyEmpty() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            panel.setReviewFiles(listOf(sampleChangedFile("src/Foo.kt")), null, emptySet(), emptyList())
            assertThat(reviewTree(panel).emptyText.text).isEqualTo("No changed files")

            toggleFilter(panel, "Hide files without comments")
            assertThat(reviewTree(panel).emptyText.text).isEqualTo("No files with comments")
        }
    }

    @Test
    fun filteringOutTheFirstFileAutoSelectsTheFirstSurvivingFile() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val first = sampleChangedFile("src/Aaa.kt")
            val second = sampleChangedFile("src/Bbb.kt")
            panel.setReviewFiles(listOf(first, second), null, emptySet(), listOf(sampleComment("src/Bbb.kt")))

            toggleFilter(panel, "Hide files without comments")

            assertThat(panel.selectedFile()?.filePath).isEqualTo("src/Bbb.kt")
        }
    }

    @Test
    fun turnModeOmitsCommentRowsAndDisablesFilters() {
        val turnService = TurnSnapshotService.getInstance(project)
        val repoRoot = Path.of(project.basePath!!)
        val file = repoRoot.resolve("src/Foo.kt")
        runGit(repoRoot, "init")
        runGit(repoRoot, "config", "user.email", "test@example.com")
        runGit(repoRoot, "config", "user.name", "Test User")
        Files.createDirectories(file.parent)
        Files.writeString(file, "before\n")
        runGit(repoRoot, "add", ".")
        runGit(repoRoot, "commit", "-m", "initial")
        Files.writeString(file, "after\n")
        turnService.beginTurn("s", "t", project.basePath!!, null, null)
        turnService.endTurn("s", "t", "completed", listOf(file.toString()), emptyList())

        // Both filters ON before the panel is built: an active "hide files without comments" must not
        // empty the turn tree, since turn mode has no comment rows to match against (design D6).
        PropertiesComponent.getInstance(project).setValue(HIDE_FILES_WITHOUT_COMMENTS_KEY, true)
        PropertiesComponent.getInstance(project).setValue(HIDE_RESOLVED_COMMENTS_KEY, true)

        onEdt {
            val panel = ChangedFilesPanel(project)
            panel.setReviewFiles(listOf(sampleChangedFile("src/Foo.kt")), null, emptySet(), listOf(sampleComment("src/Foo.kt", body = "hidden in turn")))
            panel.setTurnsEnabled(true)
            panel.refreshTurns(turnService)

            turnCombo(panel).selectedIndex = 1

            val rows = treeRowTexts(reviewTree(panel))
            assertThat(rows).noneMatch { it.contains("hidden in turn") }
            assertThat(rows).anyMatch { it.contains("Foo.kt") }

            val hideFilesAction = filterAction(panel, "Hide files without comments")
            val hideFilesEvent = TestActionEvent.createTestEvent(hideFilesAction)
            hideFilesAction.update(hideFilesEvent)
            assertThat(hideFilesEvent.presentation.isEnabled).isFalse()

            val hideResolvedAction = filterAction(panel, "Hide resolved comments")
            val hideResolvedEvent = TestActionEvent.createTestEvent(hideResolvedAction)
            hideResolvedAction.update(hideResolvedEvent)
            assertThat(hideResolvedEvent.presentation.isEnabled).isFalse()
        }
    }

    @Test
    fun headerHasNoCheckboxesAndExposesAMenuButton() {
        onEdt {
            val panel = ChangedFilesPanel(project)

            assertThat(findComponents(panel.component).filterIsInstance<JCheckBox>()).isEmpty()

            // An ActionButton, not a bare JButton: only ActionButton paints the platform's rollover
            // and pressed states, which a flat JButton (content area and border painting both off)
            // cannot do. Asserting the type is what keeps the hover behaviour from regressing.
            val menuButton = findComponents(panel.component).filterIsInstance<ActionButton>().single()
            assertThat(menuButton.action).isSameAs(panel.filterActionGroupForTest())
            assertThat(menuButton.presentation.icon).isEqualTo(AllIcons.Actions.More)
        }
    }

    @Test
    fun filterMenuButtonIsAPopupGroupSoItOpensAMenuRatherThanFiringAnAction() {
        onEdt {
            val panel = ChangedFilesPanel(project)

            // ActionButton only shows a drop-down for a group that declares itself a popup; without
            // this the button would try to invoke the group as a single action and nothing opens.
            assertThat(panel.filterActionGroupForTest().isPopup).isTrue()
        }
    }

    @Test
    fun headerFitsWithinThePanelPreferredWidth() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(sampleComment("src/Foo.kt")))

            val header = headerComponent(panel)

            // Regression for the reported defect: a header wide enough to hold both filter checkboxes
            // plus the turn combo (~550px) overlapped the panel's ~280px preferred width, so the
            // checkboxes rendered underneath the "Changed Files" caption. The kebab menu keeps the
            // header itself narrower than the panel it lives in.
            assertThat(header.preferredSize.width).isLessThanOrEqualTo(panel.component.preferredSize.width)
        }
    }

    @Test
    fun selectingCommentRowResolvesParentFileAndExposesComment() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))

            selectRowContaining(reviewTree(panel), "target")

            assertThat(panel.selectedFile()?.filePath).isEqualTo("src/Foo.kt")
            assertThat(panel.selectedComment()?.id).isEqualTo(comment.id)
        }
    }

    @Test
    fun commentRowDoesNotFireOpenOrDeleteButFileRowDoes() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val opened = mutableListOf<ChangedFile>()
            val deleted = mutableListOf<ChangedFile>()
            panel.onOpenRequested = { opened += it }
            panel.onDeleteRequested = { deleted += it }
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            val tree = reviewTree(panel)

            selectRowContaining(tree, "target")
            doubleClickSelectedRow(tree)
            panel.triggerDeleteForTest()
            assertThat(opened).isEmpty()
            assertThat(deleted).isEmpty()

            selectRowContaining(tree, "Foo.kt")
            doubleClickSelectedRow(tree)
            panel.triggerDeleteForTest()
            assertThat(opened).hasSize(1)
            assertThat(deleted).hasSize(1)
        }
    }

    @Test
    fun onCommentSelectedFiresExactlyOncePerMouseClickIncludingReclick() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val fired = mutableListOf<String>()
            panel.onCommentSelected = { fired += it.id }
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            val tree = reviewTree(panel)
            tree.clearSelection()

            // gestureOnRowContaining only invokes mouseClicked; it never presses or touches selection,
            // so this row stays unselected throughout.
            gestureOnRowContaining(tree, "target")
            assertThat(fired).containsExactly(comment.id)

            // tree.clearSelection() above is never undone, so this second call runs against the same
            // unselected row as the first — the two calls are identical, and this simply confirms
            // mouseClicked fires the callback on every invocation, not just the first.
            gestureOnRowContaining(tree, "target")
            assertThat(fired).containsExactly(comment.id, comment.id)
        }
    }

    @Test
    fun freshMouseClickOnUnselectedCommentActivatesOnlyOnce() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val fired = mutableListOf<String>()
            panel.onCommentSelected = { fired += it.id }
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            val tree = reviewTree(panel)
            tree.clearSelection()
            val row = rowIndexContaining(tree, "target")

            // Real MOUSE_PRESSED can't be dispatched here (BasicTreeUI.mousePressed hits
            // HeadlessException resolving the menu shortcut key mask). Stand in for the press-time
            // selection change with a second MouseListener that moves the selection on mouseClicked.
            // Component.addMouseListener chains via AWTEventMulticaster in add order, so the panel's
            // own listener (added in init, before this one) runs first — verified empirically: the
            // panel's onCommentSelected fired exactly once by the time this stand-in listener's
            // mouseClicked ran. Then this listener changes the selection while the dispatched
            // MOUSE_CLICKED is still EventQueue.getCurrentEvent() (confirmed by identity), which is
            // exactly the condition the guard exists to suppress: a selection change occurring while
            // an AWT mouse event is current.
            tree.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    tree.setSelectionRow(row)
                }
            })

            val bounds = tree.getRowBounds(row)
            val event = MouseEvent(
                tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                bounds.centerX.toInt(), bounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1,
            )
            // dispatchEvent (unlike calling mouseClicked directly) routes through
            // Component.dispatchEventImpl, which calls EventQueue.setCurrentEventAndMostRecentTime,
            // so EventQueue.getCurrentEvent() genuinely reflects this MouseEvent during the callback
            // chain below — the property the guard actually depends on.
            tree.dispatchEvent(event)

            assertThat(fired).containsExactly(comment.id)
        }
    }

    @Test
    fun clickingEmptySpaceOrRightClickingDoesNotActivateAComment() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val fired = mutableListOf<String>()
            panel.onCommentSelected = { fired += it.id }
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            val tree = reviewTree(panel)
            selectRowContaining(tree, "target")
            fired.clear()

            // Left-click well below the last row: selection is unchanged, but nothing was clicked.
            val empty = MouseEvent(
                tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                5, tree.rowCount * tree.rowHeight + 200, 1, false, MouseEvent.BUTTON1,
            )
            tree.mouseListeners.forEach { it.mouseClicked(empty) }
            assertThat(fired).isEmpty()

            // Right-click directly on the selected comment row.
            val bounds = tree.getRowBounds(rowIndexContaining(tree, "target"))
            val rightClick = MouseEvent(
                tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                bounds.centerX.toInt(), bounds.centerY.toInt(), 1, false, MouseEvent.BUTTON3,
            )
            tree.mouseListeners.forEach { it.mouseClicked(rightClick) }
            assertThat(fired).isEmpty()
        }
    }

    @Test
    fun clickToTheRightOfAShortCommentLabelStillActivatesIt() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val fired = mutableListOf<String>()
            panel.onCommentSelected = { fired += it.id }
            val file = sampleChangedFile("src/Foo.kt")
            // A short body renders a narrow label, leaving a wide dead zone to its right in a real
            // (wider) tool window — that gap is exactly what getPathForLocation misses.
            val comment = sampleComment("src/Foo.kt", body = "typo")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            val tree = reviewTree(panel)
            val row = rowIndexContaining(tree, "typo")
            val bounds = tree.getRowBounds(row)

            // Click well past the label's own (narrow) bounds, but still within the row's vertical band.
            val event = MouseEvent(
                tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                bounds.x + bounds.width + 100, bounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1,
            )
            tree.mouseListeners.forEach { it.mouseClicked(event) }

            assertThat(fired).containsExactly(comment.id)
        }
    }

    @Test
    fun keyboardSelectionOfCommentActivatesExactlyOnce() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val fired = mutableListOf<String>()
            panel.onCommentSelected = { fired += it.id }
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))

            // Programmatic selection stands in for keyboard navigation: no AWT mouse event is current.
            selectRowContaining(reviewTree(panel), "target")

            assertThat(fired).containsExactly(comment.id)
        }
    }

    @Test
    fun rebuildPreservesSelectedCommentAndFallsBackToFileWhenGone() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val comment = sampleComment("src/Foo.kt", body = "target")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            selectRowContaining(reviewTree(panel), "target")

            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(comment))
            assertThat(panel.selectedComment()?.id).isEqualTo(comment.id)

            panel.setReviewFiles(listOf(file), null, emptySet(), emptyList())
            assertThat(panel.selectedComment()).isNull()
            assertThat(panel.selectedFile()?.filePath).isEqualTo("src/Foo.kt")
        }
    }

    @Test
    fun commentRowsAppearUnderTheirFileInAnchorOrder() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val later = sampleComment("src/Foo.kt", body = "second", newLine = 10)
            val earlier = sampleComment("src/Foo.kt", body = "first", newLine = 2)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(later, earlier))

            val rows = treeRowTexts(reviewTree(panel))
            val fileIndex = rows.indexOfFirst { it.contains("Foo.kt") }

            assertThat(rows[fileIndex + 1]).contains("L2").contains("first")
            assertThat(rows[fileIndex + 2]).contains("L10").contains("second")
        }
    }

    @Test
    fun resolvedCommentRowIsRenderedWithCheckedIconAndOpenWithBalloon() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val open = sampleComment("src/Foo.kt", body = "open one", newLine = 1)
            val resolved = sampleComment("src/Foo.kt", body = "done one", newLine = 2, status = CommentStatus.RESOLVED)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(open, resolved))

            // Pair each row's rendered TEXT with its icon, so the assertion fails if the icons are swapped.
            // Asserting only that both icons appear somewhere would pass with the mapping reversed.
            val tree = reviewTree(panel)
            val byText = (0 until tree.rowCount).associate { row ->
                val path = tree.getPathForRow(row)
                val component = tree.cellRenderer.getTreeCellRendererComponent(
                    tree, path.lastPathComponent, false, true, true, row, false,
                ) as ColoredTreeCellRenderer
                component.toString() to component.icon
            }

            assertThat(byText.entries.single { it.key.contains("open one") }.value)
                .isEqualTo(AllIcons.General.Balloon)
            assertThat(byText.entries.single { it.key.contains("done one") }.value)
                .isEqualTo(AllIcons.Actions.Checked)
        }
    }

    @Test
    fun resolvedCommentSummaryIsGrayedWhileOpenCommentSummaryIsRegular() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val open = sampleComment("src/Foo.kt", body = "open summary", newLine = 1)
            val resolved =
                sampleComment("src/Foo.kt", body = "resolved summary", newLine = 2, status = CommentStatus.RESOLVED)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(open, resolved))
            val tree = reviewTree(panel)

            // Find the SimpleTextAttributes attached to the fragment carrying each comment's own body
            // text — the only fragment "Resolved ones... greyed" is actually about (as opposed to the
            // line-number or reply-count fragments, which are always GRAYED_ATTRIBUTES regardless).
            fun summaryAttributesForRow(bodyText: String): SimpleTextAttributes {
                val row = rowIndexContaining(tree, bodyText)
                val path = tree.getPathForRow(row)
                val component = tree.cellRenderer.getTreeCellRendererComponent(
                    tree, path.lastPathComponent, false, true, true, row, false,
                ) as ColoredTreeCellRenderer
                val iterator = component.iterator()
                var attributes: SimpleTextAttributes? = null
                while (iterator.hasNext()) {
                    val fragment = iterator.next()
                    if (fragment.contains(bodyText)) attributes = iterator.textAttributes
                }
                return requireNotNull(attributes) { "no fragment contained '$bodyText'" }
            }

            assertThat(summaryAttributesForRow("open summary")).isEqualTo(SimpleTextAttributes.REGULAR_ATTRIBUTES)
            assertThat(summaryAttributesForRow("resolved summary")).isEqualTo(SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    @Test
    fun replyCountRendersOnlyWhenThreadIsNonEmpty() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val reply = CommentReply(id = "r1", commentId = "c", author = "agent", body = "ok", createdAt = "2026-09-12T10:01:00Z")
            val withReplies = sampleComment("src/Foo.kt", body = "threaded", newLine = 1, replies = listOf(reply, reply))
            val bare = sampleComment("src/Foo.kt", body = "bare", newLine = 2)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(withReplies, bare))

            val rows = treeRowTexts(reviewTree(panel))

            assertThat(rows.single { it.contains("threaded") }).contains("↩ 2")
            assertThat(rows.single { it.contains("bare") }).doesNotContain("↩")
        }
    }

    @Test
    fun longBodyIsElidedAndHtmlPrefixIsRenderedLiterally() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val long = sampleComment("src/Foo.kt", body = "w".repeat(200), newLine = 1)
            val html = sampleComment("src/Foo.kt", body = "<html><b>bold</b>", newLine = 2)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(long, html))

            val rows = treeRowTexts(reviewTree(panel))
            val longRow = rows.single { it.contains("www") }

            assertThat(longRow).endsWith("…")
            assertThat(longRow.count { it == 'w' }).isEqualTo(TREE_SUMMARY_BUDGET - 1)
            assertThat(rows).anyMatch { it.contains("<html><b>bold</b>") }
        }
    }

    @Test
    fun commentForUnknownPathProducesNoRow() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val orphan = sampleComment("src/Gone.kt", body = "orphan")
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(orphan))

            assertThat(treeRowTexts(reviewTree(panel))).noneMatch { it.contains("orphan") }
        }
    }

    @Test
    fun commentOnRenamedFilesPreviousPathProducesNoRow() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val renamed = ChangedFile(
                filePath = "src/new/Foo.kt",
                status = ChangedFileStatus.RENAMED,
                beforeContent = ReviewContent("a\n", "before", "src/old/Foo.kt"),
                afterContent = ReviewContent("b\n", "after", "src/new/Foo.kt"),
                previousFilePath = "src/old/Foo.kt",
            )
            val oldPathComment = sampleComment("src/old/Foo.kt", body = "pre-rename")
            panel.setReviewFiles(listOf(renamed), null, emptySet(), listOf(oldPathComment))

            assertThat(treeRowTexts(reviewTree(panel))).noneMatch { it.contains("pre-rename") }
        }
    }

    @Test
    fun anchorlessCommentRendersWithoutLineLabel() {
        onEdt {
            val panel = ChangedFilesPanel(project)
            val file = sampleChangedFile("src/Foo.kt")
            val anchorless = sampleComment("src/Foo.kt", body = "no line", newLine = null)
            panel.setReviewFiles(listOf(file), null, emptySet(), listOf(anchorless))

            val row = treeRowTexts(reviewTree(panel)).single { it.contains("no line") }

            assertThat(row).doesNotContain("L")
        }
    }

    private fun selectRowContaining(tree: JTree, text: String) {
        val row = (0 until tree.rowCount).first { r -> treeRowTexts(tree)[r].contains(text) }
        tree.setSelectionRow(row)
    }

    private fun rowIndexContaining(tree: JTree, text: String): Int =
        (0 until tree.rowCount).first { treeRowTexts(tree)[it].contains(text) }

    /**
     * Invokes mouseClicked directly, without simulating a press or touching selection state.
     *
     * Headless fallback: this environment's BasicTreeUI throws HeadlessException while computing
     * the press-driven selection (it queries the menu shortcut key mask), so dispatching a real
     * MOUSE_PRESSED is unusable here. The documented fallback of `setSelectionRow` followed by
     * `clickSelectedRow` does not fit the double-fire test below: `setSelectionRow` runs outside
     * any AWT mouse event, so it would itself activate the comment via the selection-listener path
     * and double-count a fresh click. Instead, invoke `mouseClicked` directly without touching the
     * selection — the mouse listener resolves the clicked node from coordinates regardless of prior
     * selection state, so this exercises exactly the fire-once-per-click contract under test. Note
     * that this helper alone does NOT exercise the EventQueue.getCurrentEvent() guard in the
     * selection listener, since it never changes tree.selectionPath and never goes through
     * Component.dispatchEvent. keyboardSelectionOfCommentActivatesExactlyOnce only proves the
     * *non*-mouse branch of that guard. The guard's mouse branch is covered separately by
     * freshMouseClickOnUnselectedCommentActivatesOnlyOnce, which dispatches a real MOUSE_CLICKED via
     * tree.dispatchEvent so EventQueue.getCurrentEvent() is genuinely the mouse event, and pairs it
     * with a stand-in press-time selection change.
     */
    private fun gestureOnRowContaining(tree: JTree, text: String, clicks: Int = 1) {
        val bounds = tree.getRowBounds(rowIndexContaining(tree, text))
        val event = MouseEvent(
            tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            bounds.centerX.toInt(), bounds.centerY.toInt(), clicks, false, MouseEvent.BUTTON1,
        )
        tree.mouseListeners.forEach { it.mouseClicked(event) }
    }

    /** Direct listener invocation — used only where the row is already selected and no gesture is needed. */
    private fun clickSelectedRow(tree: JTree, clicks: Int = 1) {
        val bounds = tree.getRowBounds(tree.selectionRows!!.first())
        val event = MouseEvent(
            tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            bounds.centerX.toInt(), bounds.centerY.toInt(), clicks, false, MouseEvent.BUTTON1,
        )
        tree.mouseListeners.forEach { it.mouseClicked(event) }
    }

    private fun doubleClickSelectedRow(tree: JTree) = clickSelectedRow(tree, clicks = 2)

    private fun treeRowTexts(tree: JTree): List<String> = (0 until tree.rowCount).map { row ->
        val path = tree.getPathForRow(row)
        tree.cellRenderer.getTreeCellRendererComponent(
            tree,
            path.lastPathComponent,
            false,
            tree.isExpanded(path),
            tree.model.isLeaf(path.lastPathComponent),
            row,
            false,
        ).toString()
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait(action)
    }
}
