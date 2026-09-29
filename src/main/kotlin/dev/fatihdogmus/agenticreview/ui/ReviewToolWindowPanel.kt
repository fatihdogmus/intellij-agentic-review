package dev.fatihdogmus.agenticreview.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import dev.fatihdogmus.agenticreview.ReviewFileNavigator
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.VcsLogReviewSupport
import dev.fatihdogmus.agenticreview.diff.DiffRequestBuilder
import dev.fatihdogmus.agenticreview.diff.ReviewDiffPanel
import dev.fatihdogmus.agenticreview.editor.ReviewPageManager
import dev.fatihdogmus.agenticreview.export.ExportUiSupport
import dev.fatihdogmus.agenticreview.model.Review
import dev.fatihdogmus.agenticreview.model.ReviewTargetType
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshot
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.seenKey
import java.awt.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

class ReviewToolWindowPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val panelBackground = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
    private val cardBorder = UIManager.getColor("Component.borderColor") ?: JBColor.border()
    private val manager = ReviewManagerService.getInstance(project)
    private val diffRequestBuilder = DiffRequestBuilder(project)
    private val diffPanel = ReviewDiffPanel(project, this)

    val embeddedEditors: List<Editor>
        get() = diffPanel.embeddedEditors
    private val changedFilesPanel = ChangedFilesPanel()
    private val contentPanel = JPanel(BorderLayout())
    private val reviewSelector = JComboBox<Review>()
    private val createReviewButton = RoundedToolbarButton("Create Review")
    private val editReviewButton = RoundedToolbarButton("Edit")
    private val reviewSelectorPanel = createReviewSelectorPanel()
    private val mainContent = createMainContent()
    private var updatingReviewSelector = false
    private val stateListener: () -> Unit = {
        // Comment inlays are attached when a diff viewer is created, so comment mutations
        // must invalidate cached requests to force the viewer to rebuild immediately.
        diffRequestCache.clear()
        refreshUi()
    }
    private val turnStateListener: () -> Unit = { refreshUi() }
    private val changedFilesByReviewId = mutableMapOf<String, List<ChangedFile>>()
    private val changedFilesLoadSequence = AtomicInteger()
    private val turnSnapshotService = TurnSnapshotService.getInstance(project)
    private val diffRequestCache = object : LinkedHashMap<String, DiffRequest>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DiffRequest>): Boolean = size > 30
    }

    init {
        manager.addListener(stateListener)
        turnSnapshotService.addListener(turnStateListener)
        setContent(contentPanel)
        background = panelBackground
        contentPanel.add(reviewSelectorPanel, BorderLayout.NORTH)
        contentPanel.add(mainContent, BorderLayout.CENTER)

        reviewSelector.renderer = ReviewSelectorRenderer()
        reviewSelector.preferredSize = Dimension(250, reviewSelector.preferredSize.height)
        reviewSelector.addActionListener {
            if (!updatingReviewSelector) {
                manager.selectReview((reviewSelector.selectedItem as? Review)?.id)
            }
        }
        createReviewButton.applyCreateReviewStyle().addActionListener { showCreateReviewMenu() }
        editReviewButton.applyToolbarDropdownStyle().addActionListener { showEditReviewMenu() }

        changedFilesPanel.onSelectionChanged = { changedFile ->
            manager.selectFile(changedFile?.filePath)
            refreshDiff()
        }
        changedFilesPanel.onOpenRequested = { changedFile ->
            openChangedFile(changedFile)
        }
        changedFilesPanel.onDeleteRequested = { changedFile ->
            deleteChangedFile(changedFile)
        }
        changedFilesPanel.onTurnChanged = { turn ->
            refreshDiffForTurn(turn)
        }

        refreshUi()
    }

    val focusTarget: JComponent
        get() = changedFilesPanel.component

    override fun dispose() {
        manager.removeListener(stateListener)
        turnSnapshotService.removeListener(turnStateListener)
    }

    private fun refreshUi() {
        refreshReviewSelector()
        val review = manager.getCurrentReview()
        changedFilesPanel.setTurnsEnabled(review?.target?.type == ReviewTargetType.UNCOMMITTED)
        changedFilesPanel.refreshTurns(turnSnapshotService)

        if (review != null) {
            val files = changedFilesByReviewId[review.id].orEmpty()
            changedFilesPanel.setReviewFiles(
                files,
                manager.currentFilePath,
                manager.seenFileKeys(review.id),
                commentCounts(review),
            )
            loadChangedFilesIfNeeded(review)
            refreshDiff()
        } else {
            changedFilesPanel.setReviewFiles(emptyList(), null, emptySet())
            diffPanel.showDiff(MessageDiffRequest("Select review from dropdown above."))
        }
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun refreshDiff() {
        val changedFile = changedFilesPanel.selectedFile()
        val turn = changedFilesPanel.selectedTurn()
        if (turn != null) {
            refreshDiffForTurn(turn)
            return
        }
        val reviewId = manager.currentReviewId
        val review = manager.getCurrentReview()
        if (reviewId == null) {
            diffPanel.showDiff(MessageDiffRequest("Select review from dropdown above."))
        } else if (!changedFilesByReviewId.containsKey(reviewId)) {
            diffPanel.showDiff(MessageDiffRequest("Loading changed files..."))
        } else if (changedFilesByReviewId[reviewId].isNullOrEmpty()) {
            val message = if (review?.target?.type == ReviewTargetType.UNCOMMITTED) {
                "No uncommitted changes."
            } else {
                "No changed files in this review."
            }
            diffPanel.showDiff(MessageDiffRequest(message))
        } else if (changedFile == null) {
            diffPanel.showDiff(MessageDiffRequest("Select changed file to review."))
        } else {
            val repositoryRoot = review?.repositoryRoot ?: project.basePath ?: ""
            val cacheKey = "$reviewId:${changedFile.seenKey()}"
            val request = diffRequestCache.getOrPut(cacheKey) {
                try {
                    diffRequestBuilder.buildForFile(
                        reviewId,
                        changedFile,
                        repositoryRoot,
                        allowEditing = review?.target?.type == ReviewTargetType.UNCOMMITTED,
                        onEditorsCreated = { editors ->
                            diffPanel.setEmbeddedEditors(editors)
                            seedEmbeddedEditorContexts(editors)
                        },
                    )
                } catch (e: Exception) {
                    return@getOrPut MessageDiffRequest("Failed to build diff request: ${e.message}")
                }
            }
            diffPanel.showDiff(request)
            if (manager.markFileSeen(reviewId, changedFile)) {
                changedFilesPanel.setReviewFiles(
                    changedFilesByReviewId[reviewId].orEmpty(),
                    manager.currentFilePath,
                    manager.seenFileKeys(reviewId),
                    review?.let(::commentCounts).orEmpty(),
                )
            }
        }
    }

    private fun refreshDiffForTurn(turn: TurnSnapshot?) {
        if (turn == null) return
        val diffs = turnSnapshotService.getTurnDiffs(turn.id)
        if (diffs.isEmpty()) {
            diffPanel.showDiff(MessageDiffRequest("No changed files recorded for this turn."))
            return
        }
        val changedFile = changedFilesPanel.selectedFile()
        if (changedFile == null) {
            diffPanel.showDiff(MessageDiffRequest("Select changed file to review."))
            return
        }
        val matchedDiff = diffs.firstOrNull { it.filePath == changedFile.filePath }
        if (matchedDiff != null) {
            val repositoryRoot = manager.getCurrentReview()?.repositoryRoot ?: project.basePath ?: ""
            val cacheKey = "${turn.id}:${matchedDiff.seenKey()}"
            val request = diffRequestCache.getOrPut(cacheKey) {
                diffRequestBuilder.buildForFile(
                    turn.id,
                    matchedDiff,
                    repositoryRoot,
                    allowEditing = false,
                    onEditorsCreated = { editors ->
                        diffPanel.setEmbeddedEditors(editors)
                        seedEmbeddedEditorContexts(editors)
                    },
                )
            }
            diffPanel.showDiff(request)
        }
    }

    private fun seedEmbeddedEditorContexts(editors: List<Editor>) {
        val psiFilesToRestart = linkedSetOf<com.intellij.psi.PsiFile>()

        for (editor in editors) {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: continue
            if (seedEditorContextReflectively(editor, psiFile)) {
                psiFilesToRestart += psiFile
            }
        }

        if (psiFilesToRestart.isEmpty()) return

        val daemon = DaemonCodeAnalyzer.getInstance(project)
        psiFilesToRestart.forEach { psiFile ->
            daemon.restart(psiFile, "Seed diff editor context")
        }
    }

    private fun seedEditorContextReflectively(editor: Editor, psiFile: com.intellij.psi.PsiFile): Boolean {
        return try {
            val managerClass = Class.forName("com.intellij.codeInsight.multiverse.EditorContextManager")
            val manager = managerClass.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return false

            val cachedMethod = managerClass.methods.firstOrNull {
                it.name == "getCachedEditorContexts" && it.parameterCount == 1
            }
            if (cachedMethod?.invoke(manager, editor) != null) return false

            val context = resolveEditorContextReflectively(psiFile) ?: return false
            val singleContextClass = Class.forName("com.intellij.codeInsight.multiverse.SingleEditorContext")
            val singleContext = singleContextClass.constructors.firstOrNull { it.parameterCount == 1 }?.newInstance(context)
                ?: return false

            val setter = managerClass.methods.firstOrNull {
                it.name == "setEditorContextNoFire" && it.parameterCount == 2
            } ?: managerClass.methods.firstOrNull {
                it.name == "setEditorContext" && it.parameterCount == 2
            } ?: return false

            setter.invoke(manager, editor, singleContext)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveEditorContextReflectively(psiFile: com.intellij.psi.PsiFile): Any? {
        return try {
            val utilClass = Class.forName("com.intellij.codeInsight.multiverse.CodeInsightContextUtil")
            utilClass.getMethod("getCodeInsightContext", com.intellij.psi.PsiFile::class.java).invoke(null, psiFile)
        } catch (_: Throwable) {
            try {
                val contextsClass = Class.forName("com.intellij.codeInsight.multiverse.CodeInsightContexts")
                contextsClass.getMethod("defaultContext").invoke(null)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun deleteChangedFile(changedFile: ChangedFile) {
        val review = manager.getCurrentReview() ?: return
        val virtualFile =
            LocalFileSystem.getInstance().findFileByNioFile(Path.of(review.repositoryRoot, changedFile.filePath))
                ?: return
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
        DeleteHandler.deletePsiElement(arrayOf(psiFile), project)
    }

    private fun loadChangedFilesIfNeeded(review: Review) {
        if (review.target.type != ReviewTargetType.UNCOMMITTED && changedFilesByReviewId.containsKey(review.id)) return
        val requestId = changedFilesLoadSequence.incrementAndGet()
        object : Task.Backgroundable(project, "Loading review changes", false) {
            private var changedFiles: List<ChangedFile> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                changedFiles = manager.loadChangedFiles(review)
            }

            override fun onSuccess() {
                if (requestId != changedFilesLoadSequence.get()) return
                changedFilesByReviewId[review.id] = changedFiles
                manager.syncSeenFiles(review.id, changedFiles, notify = false)
                if (manager.currentReviewId == review.id) {
                    changedFilesPanel.setReviewFiles(
                        changedFiles,
                        manager.currentFilePath,
                        manager.seenFileKeys(review.id),
                        commentCounts(review),
                    )
                    refreshDiff()
                }
            }
        }.queue()
    }

    private fun createMainContent(): JComponent {
        val top = changedFilesPanel.component.apply {
            preferredSize = Dimension(400, 140)
            minimumSize = Dimension(280, 90)
        }
        val center = diffPanel.component.apply {
            minimumSize = Dimension(320, 240)
        }

        return OnePixelSplitter(false, "agentic.review.changed.files.splitter", 0.20f).apply {
            firstComponent = top
            secondComponent = center
        }
    }

    private fun commentCounts(review: Review): Map<String, Int> =
        review.comments.groupingBy { it.filePath }.eachCount()

    private fun createReviewSelectorPanel(): JComponent = createCardPanel().apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(cardBorder, 0, 0, 1, 0),
            JBUI.Borders.empty(8, 12),
        )

        val controls = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(reviewSelector, BorderLayout.CENTER)
            add(editReviewButton, BorderLayout.EAST)
        }

        val primaryActions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(createReviewButton)
            add(
                RoundedToolbarButton("Copy Prompt").applyToolbarActionStyle()
                    .apply { addActionListener { copyPrompt() } })
        }

        val rightSide = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            add(primaryActions, BorderLayout.WEST)
            add(controls, BorderLayout.CENTER)
        }

        add(rightSide, BorderLayout.CENTER)
    }

    private fun refreshReviewSelector() {
        val reviews = manager.listReviews().sortedWith(
            compareByDescending<Review> { it.target.type == ReviewTargetType.UNCOMMITTED }
                .thenByDescending { it.updatedAt },
        )
        updatingReviewSelector = true
        try {
            reviewSelector.removeAllItems()
            reviews.forEach(reviewSelector::addItem)
            val selected = reviews.firstOrNull { it.id == manager.currentReviewId }
            reviewSelector.selectedItem = selected
            createReviewButton.isEnabled = true
            editReviewButton.isEnabled = selected != null
        } finally {
            updatingReviewSelector = false
        }
    }

    private fun showCreateReviewMenu() {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Pick in VCS Log") {
                override fun actionPerformed(event: AnActionEvent) {
                    startCommitReview()
                }
            })
            add(object : DumbAwareAction("Branch Review") {
                override fun actionPerformed(event: AnActionEvent) {
                    startBranchReview()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canCreateBranchReview()
                }
            })
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(createReviewButton),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                null,
                -1,
                null,
                ActionPlaces.POPUP,
            )
            .showUnderneathOf(createReviewButton)
    }

    private fun showEditReviewMenu() {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh Branch Review") {
                override fun actionPerformed(event: AnActionEvent) {
                    refreshSelectedBranchReview()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canRefreshBranchReview(selectedReview())
                }
            })
            add(object : DumbAwareAction("Rename") {
                override fun actionPerformed(event: AnActionEvent) {
                    renameSelectedReview()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canSaveReview(selectedReview())
                }
            })
            add(object : DumbAwareAction("Save") {
                override fun actionPerformed(event: AnActionEvent) {
                    saveSelectedReview()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canSaveReview(selectedReview())
                }
            })
            add(object : DumbAwareAction("Delete") {
                override fun actionPerformed(event: AnActionEvent) {
                    deleteSelectedReview()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canSaveReview(selectedReview())
                }
            })
            add(object : DumbAwareAction("Load") {
                override fun actionPerformed(event: AnActionEvent) {
                    loadReviewFromFile()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = manager.canSaveReview(selectedReview())
                }
            })
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(editReviewButton),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                null,
                -1,
                null,
                ActionPlaces.POPUP,
            )
            .showUnderneathOf(editReviewButton)
    }

    private fun renameSelectedReview() {
        val review = selectedReview() ?: return
        val newTitle = Messages.showInputDialog(
            project,
            "Review name",
            "Rename Review",
            Messages.getQuestionIcon(),
            review.title,
            null,
        ) ?: return
        manager.renameReview(review.id, newTitle)
    }

    private fun deleteSelectedReview() {
        val review = selectedReview() ?: return
        manager.deleteReview(review.id)
    }

    private fun saveSelectedReview() {
        val review = selectedReview() ?: return
        val newTitle = Messages.showInputDialog(
            project,
            "Review name",
            "Save Review",
            Messages.getQuestionIcon(),
            review.title,
            null,
        ) ?: return
        val plan = manager.prepareSaveReview(review.id, newTitle) ?: return
        if (plan.fileExists) {
            val overwrite = Messages.showYesNoDialog(
                project,
                "Review file already exists:\n${plan.filePath.fileName}\n\nOverwrite it?",
                "Overwrite Review File",
                Messages.getWarningIcon(),
            ) == Messages.YES
            if (!overwrite) return
        }
        object : Task.Backgroundable(project, "Saving review", false) {
            override fun run(indicator: ProgressIndicator) {
                manager.saveReviewToFile(plan)
            }
        }.queue()
    }

    private fun loadReviewFromFile() {
        val rootPath = manager.getCurrentReview()?.repositoryRoot ?: project.basePath ?: return
        val rootFile = LocalFileSystem.getInstance().findFileByPath(rootPath)
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").apply {
            title = "Load Review"
            description = "Select a saved Agentic Review JSON file"
        }
        FileChooser.chooseFile(descriptor, project, rootFile) { selectedFile ->
            object : Task.Backgroundable(project, "Loading review", false) {
                private var errorMessage: String? = null

                override fun run(indicator: ProgressIndicator) {
                    errorMessage = manager.loadReviewFromFile(Path.of(selectedFile.path)).error
                }

                override fun onSuccess() {
                    errorMessage?.let {
                        Messages.showErrorDialog(project, it, "Load Review Failed")
                    }
                }
            }.queue()
        }
    }

    private fun selectedReview(): Review? = (reviewSelector.selectedItem as? Review) ?: manager.getCurrentReview()

    private fun startCommitReview() {
        VcsLogReviewSupport.openLogAndPromptSelection(project)
    }

    private fun startBranchReview() {
        runReviewCreationTask("Creating branch review") {
            manager.createBranchReview()
        }
    }

    private fun refreshSelectedBranchReview() {
        val review = selectedReview()?.takeIf(manager::canRefreshBranchReview) ?: return
        object : Task.Backgroundable(project, "Refreshing branch review", false) {
            private var refreshed = false

            override fun run(indicator: ProgressIndicator) {
                refreshed = manager.refreshBranchReview(review.id)
            }

            override fun onSuccess() {
                if (!refreshed) {
                    Messages.showWarningDialog(
                        project,
                        "Switch to the review's branch and make sure its base branch is available.",
                        "Refresh Branch Review",
                    )
                    return
                }
                changedFilesLoadSequence.incrementAndGet()
                changedFilesByReviewId.remove(review.id)
                diffRequestCache.clear()
                refreshUi()
            }
        }.queue()
    }

    private fun copyPrompt() {
        val review = manager.getCurrentReview() ?: return
        object : Task.Backgroundable(project, "Building review prompt", false) {
            private var prompt: String? = null

            override fun run(indicator: ProgressIndicator) {
                prompt = manager.buildAgentPrompt(review.id)
            }

            override fun onSuccess() {
                prompt?.let { ExportUiSupport.copyToClipboard(project, it) }
            }
        }.queue()
    }

    private fun openChangedFile(changedFile: ChangedFile) {
        val review = manager.getCurrentReview() ?: return
        ReviewFileNavigator.openChangedFile(project, review.repositoryRoot, changedFile)
    }

    private fun runReviewCreationTask(title: String, action: () -> Unit) {
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                action()
            }

            override fun onSuccess() {
                ReviewPageManager.getInstance(project).open()
            }
        }.queue()
    }
}

private class ReviewSelectorRenderer : ListCellRenderer<Review> {
    private val delegate = DefaultListCellRenderer()

    override fun getListCellRendererComponent(
        list: JList<out Review>,
        value: Review?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (component is JLabel) {
            component.text = value?.let {
                val openCount =
                    it.comments.count { comment -> comment.status == dev.fatihdogmus.agenticreview.model.CommentStatus.OPEN }
                val resolvedCount =
                    it.comments.count { comment -> comment.status != dev.fatihdogmus.agenticreview.model.CommentStatus.OPEN }
                val countText = listOfNotNull(
                    openCount.takeIf { count -> count > 0 }?.let { count -> "$count Open" },
                    resolvedCount.takeIf { count -> count > 0 }?.let { count -> "$count Resolved" },
                ).joinToString(" ")
                if (countText.isEmpty()) it.title else "${it.title} · $countText"
            } ?: ""
        }
        return component
    }
}

private fun createCardPanel(): JPanel = JPanel(BorderLayout(12, 0)).apply {
    background = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
    isOpaque = true
}

private fun JButton.applyToolbarActionStyle(): JButton = apply {
    background = UIManager.getColor("Button.background") ?: JBColor.background()
    foreground = UIManager.getColor("Button.foreground") ?: JBColor.foreground()
    border = JBUI.Borders.empty(8, 16)
    isOpaque = false
    isContentAreaFilled = false
    isFocusPainted = false
    putClientProperty(ROUNDED_BUTTON_BORDER_COLOR, UIManager.getColor("Component.borderColor") ?: JBColor.border())
}

private fun JButton.applyToolbarDropdownStyle(): JButton = applyToolbarActionStyle().apply {
    icon = AllIcons.General.ArrowDown
    horizontalTextPosition = SwingConstants.LEFT
    iconTextGap = JBUI.scale(8)
}

private fun JButton.applyCreateReviewStyle(): JButton = apply {
    background = JBColor(Color(0x2E, 0xA4, 0x4F), Color(0x2E, 0xA4, 0x4F))
    foreground = JBColor(Color.WHITE, Color.WHITE)
    icon = AllIcons.General.ArrowDown
    horizontalTextPosition = SwingConstants.LEFT
    iconTextGap = JBUI.scale(8)
    border = JBUI.Borders.empty(8, 16)
    isOpaque = false
    isContentAreaFilled = false
    isFocusPainted = false
    putClientProperty(ROUNDED_BUTTON_BORDER_COLOR, JBColor(Color(0x22, 0x83, 0x3E), Color(0x1D, 0x6D, 0x34)))
}

private const val ROUNDED_BUTTON_BORDER_COLOR = "local.review.roundedButtonBorderColor"

private class RoundedToolbarButton(text: String) : JButton(text) {
    private val arc = JBUI.scale(18)

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            val borderColor = getClientProperty(ROUNDED_BUTTON_BORDER_COLOR) as? Color
            if (borderColor != null) {
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(graphics)
    }
}
