package dev.fatihdogmus.agenticreview.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
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
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTree

@TestApplication
class ChangedFilesPanelIntegrationTest {
    private val project by projectFixture()

    @BeforeEach
    fun resetTurns() {
        TurnSnapshotService.getInstance(project).clearAll(notify = false)
    }

    @Test
    fun setReviewFilesSelectsFirstFileAndTracksCurrentFiles() {
        onEdt {
            val panel = ChangedFilesPanel()
            val files = listOf(sampleChangedFile("src/Foo.kt"), sampleChangedFile("src/Bar.kt"))

            panel.setReviewFiles(files, selectedFilePath = null, seenFileKeys = emptySet())

            assertThat(panel.currentFiles()).containsExactlyElementsOf(files)
            assertThat(panel.selectedFile()?.filePath).isEqualTo("src/Foo.kt")
        }
    }

    @Test
    fun treeRendererMarksUnseenFilesWithAsterisk() {
        onEdt {
            val panel = ChangedFilesPanel()
            val unseen = sampleChangedFile("src/Foo.kt")
            val seen = sampleChangedFile("src/Bar.kt")
            panel.setReviewFiles(listOf(unseen, seen), selectedFilePath = null, seenFileKeys = setOf(seen.seenKey()))

            val rowTexts = treeRowTexts(reviewTree(panel))

            assertThat(rowTexts).anyMatch { it.contains("* Foo.kt") }
            assertThat(rowTexts).anyMatch { it.contains("Bar.kt") && !it.contains("* Bar.kt") }
        }
    }

    @Test
    fun treeRendererShowsCompactRenameStatusAndLineStats() {
        onEdt {
            val panel = ChangedFilesPanel()
            val renamed = ChangedFile(
                filePath = "src/new/Foo.kt",
                status = ChangedFileStatus.RENAMED,
                beforeContent = ReviewContent("one\ntwo", "before", "src/old/Foo.kt"),
                afterContent = ReviewContent("one\nthree\nfour", "after", "src/new/Foo.kt"),
                previousFilePath = "src/old/Foo.kt",
            )
            panel.setReviewFiles(listOf(renamed), selectedFilePath = null, seenFileKeys = emptySet())

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
            val panel = ChangedFilesPanel()
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
            panel.setReviewFiles(listOf(modified), selectedFilePath = null, seenFileKeys = emptySet())

            val rowTexts = treeRowTexts(reviewTree(panel))
            val fileRow = rowTexts.single { it.contains("Foo.kt") }

            assertThat(fileRow).contains("+2")
            assertThat(fileRow).contains("-2")
        }
    }

    @Test
    fun treeRendererShowsCommentCountNextToFileName() {
        onEdt {
            val panel = ChangedFilesPanel()
            panel.setReviewFiles(
                listOf(sampleChangedFile("src/Foo.kt"), sampleChangedFile("src/Bar.kt")),
                selectedFilePath = null,
                seenFileKeys = emptySet(),
                commentCounts = mapOf("src/Foo.kt" to 2),
            )

            val rows = treeRowTexts(reviewTree(panel))
            assertThat(rows).anyMatch { it.contains("Foo.kt  🗨︎ 2  M  +") }
            assertThat(rows).anyMatch { it.contains("Bar.kt") && !it.contains("🗨︎") }

            panel.setReviewFiles(
                listOf(sampleChangedFile("src/Foo.kt")),
                selectedFilePath = null,
                seenFileKeys = emptySet(),
                commentCounts = mapOf("src/Foo.kt" to 1),
            )
            assertThat(treeRowTexts(reviewTree(panel))).anyMatch { it.contains("Foo.kt  🗨︎ 1  M") }
        }
    }

    @Test
    fun treeCompactsSingleChildDirectoryChains() {
        onEdt {
            val panel = ChangedFilesPanel()
            panel.setReviewFiles(
                listOf(
                    sampleChangedFile("src/main/kotlin/dev/fatihdogmus/Foo.kt"),
                    sampleChangedFile("src/main/kotlin/dev/fatihdogmus/Bar.kt"),
                ),
                selectedFilePath = null,
                seenFileKeys = emptySet(),
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
            val panel = ChangedFilesPanel()
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
            val panel = ChangedFilesPanel()
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
