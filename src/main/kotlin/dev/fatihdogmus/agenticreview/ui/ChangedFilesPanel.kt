package dev.fatihdogmus.agenticreview.ui

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.PlatformIcons
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.fatihdogmus.agenticreview.model.CommentStatus
import dev.fatihdogmus.agenticreview.model.ReviewComment
import dev.fatihdogmus.agenticreview.model.thread
import dev.fatihdogmus.agenticreview.reviewCommentOrder
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshot
import dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotService
import dev.fatihdogmus.agenticreview.vcs.ChangedFile
import dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus
import dev.fatihdogmus.agenticreview.vcs.seenKey
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class ChangedFilesPanel(private val project: Project) {
    private val rootNode = DefaultMutableTreeNode(RootNode)
    private val model = DefaultTreeModel(rootNode)
    private val tree = Tree(model)
    private var updatingModel = false
    private var reviewFiles: List<ChangedFile> = emptyList()
    private var reviewComments: List<ReviewComment> = emptyList()
    private var turnFilesById: Map<String, List<ChangedFile>> = emptyMap()
    private var seenFileKeys: Set<String> = emptySet()
    private var selectedFilePath: String? = null
    private var turnsEnabled = false
    private val titleLabel = JBLabel("Changed Files")
    private val turnCombo = JComboBox<TurnComboItem>()
    private val properties = PropertiesComponent.getInstance(project)
    private val filterActionsGroup = DefaultActionGroup().apply {
        // Marked as a popup group so ActionButton renders it as a drop-down menu button rather than
        // invoking a single action, and so the platform paints its own hover/pressed states.
        isPopup = true
        templatePresentation.icon = AllIcons.Actions.More
        templatePresentation.text = "Filter Options"
        templatePresentation.description = "Filter which files and comments the tree shows"
        add(
            FilterToggleAction(
                "Hide files without comments",
                "Show only files that have review comments",
                HIDE_FILES_WITHOUT_COMMENTS_KEY,
            )
        )
        add(
            FilterToggleAction(
                "Hide resolved comments",
                "Hide comments that have been resolved",
                HIDE_RESOLVED_COMMENTS_KEY,
            )
        )
    }
    var onSelectionChanged: ((ChangedFile?) -> Unit)? = null
    var onOpenRequested: ((ChangedFile) -> Unit)? = null
    var onDeleteRequested: ((ChangedFile) -> Unit)? = null
    var onTurnChanged: ((TurnSnapshot?) -> Unit)? = null
    var onCommentSelected: ((ReviewComment) -> Unit)? = null

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        tree.cellRenderer = ChangedFileTreeRenderer { changedFile ->
            if (selectedTurn() != null) null else changedFile.seenKey() !in seenFileKeys
        }
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "No changed files"
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.background = UIManager.getColor("Tree.background") ?: UIUtil.getTreeBackground()
        tree.border = JBUI.Borders.empty(4, 6)

        turnCombo.renderer = TurnComboRenderer()
        turnCombo.addActionListener {
            refreshModel(autoSelectFirst = true, notifySelection = true)
            val selected = (turnCombo.selectedItem as? TurnComboItem)?.turn
            onTurnChanged?.invoke(selected)
        }
        tree.addTreeSelectionListener {
            if (!updatingModel) {
                tree.requestFocusInWindow()
                onSelectionChanged?.invoke(selectedFile())
                // Mouse-driven selection is activated by mouseClicked instead, so it fires exactly once.
                if (EventQueue.getCurrentEvent() !is java.awt.event.MouseEvent) {
                    selectedComment()?.let { onCommentSelected?.invoke(it) }
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                tree.requestFocusInWindow()
            }

            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                tree.requestFocusInWindow()
                when (e.clickCount) {
                    // Sole activator for mouse gestures — see the ownership rule above.
                    // Resolve the CLICKED node rather than reading selectedComment(): a left-click on empty
                    // tree space, or a right-click anywhere, leaves the selection intact and would otherwise
                    // re-activate whatever comment happens to be selected.
                    1 -> if (SwingUtilities.isLeftMouseButton(e)) {
                        // getPathForLocation is strict rectangle containment against the renderer's
                        // PREFERRED width (the label's width, not the row's), so it returns null for any
                        // click past a short label even though wide-selection highlights the whole row.
                        // Use the row's vertical band instead: closest row by y, then confirm y actually
                        // falls within that row rather than trusting the (possibly out-of-range) result.
                        // The `row >= 0` guard and the `e.y >= it.y` half of the band check below are
                        // defensive, not load-bearing: getClosestRowForLocation returns -1 only on an
                        // empty tree (where getRowBounds(-1) returns null harmlessly), and row 0 is
                        // always a directory or file node, so a click above it can never resolve to a
                        // CommentNode.
                        val row = tree.getClosestRowForLocation(e.x, e.y)
                        val bounds = if (row >= 0) tree.getRowBounds(row) else null
                        val clicked = bounds?.takeIf { e.y >= it.y && e.y < it.y + it.height }
                            ?.let { tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode }
                        (clicked?.userObject as? CommentNode)?.comment?.let { onCommentSelected?.invoke(it) }
                    }
                    2 -> selectedFileRow()?.takeIf { it.status != ChangedFileStatus.DELETED }
                        ?.let { onOpenRequested?.invoke(it) }
                }
            }
        })
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = deleteSelectedFileRow()
        }.registerCustomShortcutSet(
            ActionManager.getInstance().getAction(IdeActions.ACTION_DELETE).shortcutSet,
            component
        )
        component.preferredSize = Dimension(JBUI.scale(280), JBUI.scale(260))
        component.minimumSize = Dimension(JBUI.scale(220), JBUI.scale(180))
        component.background = tree.background
        component.add(createHeader(), BorderLayout.NORTH)
        component.add(JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            viewport.background = tree.background
        }, BorderLayout.CENTER)
    }

    fun setReviewFiles(
        files: List<ChangedFile>,
        selectedFilePath: String?,
        seenFileKeys: Set<String>,
        comments: List<ReviewComment>,
    ) {
        this.reviewFiles = files
        this.reviewComments = comments
        this.selectedFilePath = selectedFilePath
        this.seenFileKeys = seenFileKeys
        val item = turnCombo.selectedItem as? TurnComboItem
        if (item == null || item.turn == null) {
            refreshModel(autoSelectFirst = true, notifySelection = false)
        }
    }

    fun refreshTurns(turnSnapshotService: TurnSnapshotService) {
        if (!turnsEnabled) {
            turnFilesById = emptyMap()
            turnCombo.removeAllItems()
            turnCombo.addItem(TurnComboItem("Review Changes", null))
            turnCombo.selectedIndex = 0
            turnCombo.isVisible = false
            titleLabel.text = "Changed Files"
            return
        }
        val previousId = (turnCombo.selectedItem as? TurnComboItem)?.turn?.id
        turnFilesById =
            turnSnapshotService.getCompletedTurns().associate { it.id to turnSnapshotService.getTurnDiffs(it.id) }
        turnCombo.removeAllItems()
        turnCombo.addItem(TurnComboItem("Review Changes", null))
        for (turn in turnSnapshotService.getCompletedTurns()) {
            turnCombo.addItem(TurnComboItem(turnLabel(turn), turn))
        }
        val selectIndex = if (previousId != null) {
            (0 until turnCombo.itemCount).indexOfFirst { i ->
                turnCombo.getItemAt(i).turn?.id == previousId
            }.coerceAtLeast(0)
        } else 0
        turnCombo.selectedIndex = selectIndex
        turnCombo.isVisible = true
    }

    fun setTurnsEnabled(enabled: Boolean) {
        if (turnsEnabled == enabled) return
        turnsEnabled = enabled
        if (!enabled) {
            turnCombo.selectedIndex = 0
            refreshModel(autoSelectFirst = true, notifySelection = false)
        }
    }

    private fun turnLabel(turn: TurnSnapshot): String {
        val agent = turn.agent ?: "unknown"
        val started = try {
            Instant.parse(turn.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(FMT)
        } catch (_: Exception) {
            "?"
        }
        val duration = if (turn.endedAt != null) {
            try {
                val d = Duration.between(Instant.parse(turn.startedAt), Instant.parse(turn.endedAt))
                "${d.seconds}s"
            } catch (_: Exception) {
                ""
            }
        } else ""
        val count = turn.changedPaths.size
        return "$started  $agent  $duration  $count files"
    }

    /** A comment row resolves to its parent file. */
    fun selectedFile(): ChangedFile? = selectedFileNode()?.file

    fun selectedComment(): ReviewComment? = selectedCommentNode()?.comment

    fun selectedTurn(): TurnSnapshot? = (turnCombo.selectedItem as? TurnComboItem)?.turn

    fun currentFiles(): List<ChangedFile> {
        val turn = selectedTurn()
        return turn?.let { turnFilesById[it.id].orEmpty() } ?: reviewFiles
    }

    private fun createHeader(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = component.background
        border = JBUI.Borders.empty(8, 12, 4, 12)
        add(titleLabel.apply {
            foreground = UIManager.getColor("Label.foreground")
            font = font.deriveFont(font.style or Font.BOLD)
        }, BorderLayout.WEST)
        // ActionButton rather than a bare JButton: it paints the platform's own rollover and
        // pressed backgrounds via ActionButtonLook. A JButton with isContentAreaFilled/
        // isBorderPainted/isOpaque all false — which is what it takes to get a flat icon button —
        // leaves the LAF nothing to paint a hover state on, so the button looked inert.
        val filterMenuButton = ActionButton(
            filterActionsGroup,
            filterActionsGroup.templatePresentation.clone(),
            FILTER_ACTIONS_PLACE,
            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
        ).apply {
            toolTipText = "Filter options"
        }
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(turnCombo.apply { toolTipText = "Select turn to view its changes" })
            add(filterMenuButton)
        }, BorderLayout.EAST)
    }

    private fun refreshModel(autoSelectFirst: Boolean, notifySelection: Boolean) {
        val previousSelectionPath = selectedFile()?.filePath ?: selectedFilePath
        val previousCommentId = selectedComment()?.id
        val turn = selectedTurn()
        val visibleFiles = turn?.let { turnFilesById[it.id].orEmpty() } ?: reviewFiles

        titleLabel.text = if (turn != null) "Turn Changed Files" else "Changed Files"

        val filtersActive = turn == null
        val hideFilesWithoutCommentsSelected = properties.getBoolean(HIDE_FILES_WITHOUT_COMMENTS_KEY, false)
        val hideResolvedCommentsSelected = properties.getBoolean(HIDE_RESOLVED_COMMENTS_KEY, false)

        val effectiveComments = when {
            turn != null -> emptyList()
            hideResolvedCommentsSelected -> reviewComments.filter { it.status != CommentStatus.RESOLVED }
            else -> reviewComments
        }
        val effectiveFiles = if (filtersActive && hideFilesWithoutCommentsSelected) {
            val commentedPaths = effectiveComments.mapTo(hashSetOf()) { it.filePath }
            visibleFiles.filter { it.filePath in commentedPaths }
        } else {
            visibleFiles
        }
        tree.emptyText.text = if (filtersActive && hideFilesWithoutCommentsSelected) {
            "No files with comments"
        } else {
            "No changed files"
        }

        val wasUpdatingModel = updatingModel
        updatingModel = true
        try {
            rootNode.removeAllChildren()
            val fileNodesByPath = buildTree(effectiveFiles, effectiveComments)
            model.reload()
            TreeUtil.expandAll(tree)

            val commentNodeToSelect = previousCommentId?.let { id ->
                previousSelectionPath?.let(fileNodesByPath::get)
                    ?.children()?.asSequence()
                    ?.filterIsInstance<DefaultMutableTreeNode>()
                    ?.firstOrNull { (it.userObject as? CommentNode)?.comment?.id == id }
            }

            val nodeToSelect = commentNodeToSelect ?: run {
                val pathToSelect = when {
                    previousSelectionPath != null && previousSelectionPath in fileNodesByPath -> previousSelectionPath
                    autoSelectFirst -> effectiveFiles.firstOrNull()?.filePath
                    else -> null
                }
                pathToSelect?.let(fileNodesByPath::get)
            }

            tree.selectionPath = nodeToSelect?.let { TreePath(it.path) }
            if (tree.selectionPath == null) {
                tree.clearSelection()
            }
        } finally {
            updatingModel = wasUpdatingModel
        }

        if (notifySelection) {
            val currentSelectionPath = selectedFile()?.filePath
            if (currentSelectionPath != previousSelectionPath || currentSelectionPath == null) {
                onSelectionChanged?.invoke(selectedFile())
            }
        }
    }

    private fun buildTree(
        files: List<ChangedFile>,
        comments: List<ReviewComment>,
    ): Map<String, DefaultMutableTreeNode> {
        val root = DirectoryBuilder("", "")
        val fileNodes = linkedMapOf<String, DefaultMutableTreeNode>()
        val commentsByPath = comments.groupBy { it.filePath }

        for (file in files.sortedBy { it.filePath }) {
            val parts = file.filePath.split('/').filter { it.isNotBlank() }
            var directory = root
            var directoryPath = ""
            for (part in parts.dropLast(1)) {
                directoryPath = if (directoryPath.isEmpty()) part else "$directoryPath/$part"
                directory = directory.children.getOrPut(part) {
                    DirectoryBuilder(part, directoryPath)
                }
            }
            directory.files.add(file)
        }

        appendDirectoryChildren(root, rootNode, fileNodes, commentsByPath)
        return fileNodes
    }

    private fun appendDirectoryChildren(
        directory: DirectoryBuilder,
        parentNode: DefaultMutableTreeNode,
        fileNodes: MutableMap<String, DefaultMutableTreeNode>,
        commentsByPath: Map<String, List<ReviewComment>>,
    ) {
        for (child in directory.children.values.sortedBy { it.name }) {
            val compacted = compactDirectory(child)
            val directoryNode = DefaultMutableTreeNode(DirectoryNode(compacted.name, compacted.path))
            parentNode.add(directoryNode)
            appendDirectoryChildren(compacted, directoryNode, fileNodes, commentsByPath)
        }

        for (file in directory.files.sortedBy { it.filePath.substringAfterLast('/') }) {
            val fileNode = DefaultMutableTreeNode(FileNode(file))
            parentNode.add(fileNode)
            fileNodes[file.filePath] = fileNode
            commentsByPath[file.filePath].orEmpty()
                .sortedWith(reviewCommentOrder)
                .forEach { comment -> fileNode.add(DefaultMutableTreeNode(CommentNode(comment))) }
        }
    }

    private fun compactDirectory(directory: DirectoryBuilder): DirectoryBuilder {
        var compacted = directory
        while (compacted.files.isEmpty() && compacted.children.size == 1) {
            val child = compacted.children.values.single()
            compacted = DirectoryBuilder("${compacted.name}/${child.name}", child.path).apply {
                children.putAll(child.children)
                files.addAll(child.files)
            }
        }
        return compacted
    }

    /** The file whose diff should be displayed: a file row itself, or the parent file of a comment row. */
    private fun selectedFileNode(): FileNode? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val userObject = node.userObject) {
            is FileNode -> userObject
            is CommentNode -> (node.parent as? DefaultMutableTreeNode)?.userObject as? FileNode
            else -> null
        }
    }

    /** Strict: only a file row itself. Drives open and delete so comment rows never inherit them. */
    private fun selectedFileRow(): ChangedFile? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileNode)?.file

    private fun selectedCommentNode(): CommentNode? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? CommentNode

    private fun deleteSelectedFileRow() {
        selectedFileRow()?.takeIf { it.status != ChangedFileStatus.DELETED }?.let { onDeleteRequested?.invoke(it) }
    }

    @org.jetbrains.annotations.TestOnly
    internal fun triggerDeleteForTest() = deleteSelectedFileRow()

    @org.jetbrains.annotations.TestOnly
    internal fun filterActionGroupForTest(): DefaultActionGroup = filterActionsGroup

    private inner class FilterToggleAction(
        text: String,
        description: String,
        private val key: String,
    ) : ToggleAction(text, description, null), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean = properties.getBoolean(key, false)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            properties.setValue(key, state)
            refreshModel(autoSelectFirst = true, notifySelection = true)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            // Filters apply to review comments, which turn mode does not show (design D6).
            e.presentation.isEnabled = selectedTurn() == null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private data class TurnComboItem(
        val label: String,
        val turn: TurnSnapshot?,
    ) {
        override fun toString(): String = label
    }

    private class TurnComboRenderer : ListCellRenderer<TurnComboItem> {
        private val delegate = DefaultListCellRenderer()

        override fun getListCellRendererComponent(
            list: JList<out TurnComboItem>,
            value: TurnComboItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val comp = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (comp is JLabel) {
                comp.text = value?.label ?: ""
            }
            return comp
        }
    }

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

private object RootNode

private class DirectoryBuilder(
    val name: String,
    val path: String,
) {
    val children: MutableMap<String, DirectoryBuilder> = linkedMapOf()
    val files: MutableList<ChangedFile> = mutableListOf()
}

private data class DirectoryNode(
    val name: String,
    val path: String,
)

private data class FileNode(
    val file: ChangedFile,
)

private data class CommentNode(
    val comment: ReviewComment,
)

internal const val TREE_SUMMARY_BUDGET = 60
internal const val HIDE_FILES_WITHOUT_COMMENTS_KEY = "agentic.review.tree.hideFilesWithoutComments"
internal const val HIDE_RESOLVED_COMMENTS_KEY = "agentic.review.tree.hideResolvedComments"
private const val FILTER_ACTIONS_PLACE = "AgenticReview.ChangedFilesFilters"

private class ChangedFileTreeRenderer(
    private val unseenState: (ChangedFile) -> Boolean?,
) : ColoredTreeCellRenderer() {
    private val addedColor = JBColor(0x1A7F37, 0x3FB950)
    private val deletedColor = JBColor(0xCF222E, 0xF85149)
    private val lineStatsCache = mutableMapOf<String, LineStats>()

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        when (userObject) {
            is DirectoryNode -> renderDirectory(userObject)
            is FileNode -> renderFile(userObject.file)
            is CommentNode -> renderComment(userObject.comment)
        }
    }

    private fun renderDirectory(directory: DirectoryNode) {
        icon = PlatformIcons.FOLDER_ICON
        append(directory.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    private fun renderFile(file: ChangedFile) {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.filePath)
        val unseen = unseenState(file)
        val lineStats = estimateLineStats(file)
        val textAttributes =
            if (unseen == true) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        val name = file.filePath.substringAfterLast('/')

        icon = fileType.icon
        append(if (unseen == true) "* $name" else name, textAttributes)
        append("  ${statusText(file.status)}", statusAttributes(file.status))
        append("  +${lineStats.added}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addedColor))
        append(" -${lineStats.deleted}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, deletedColor))

        file.previousFilePath?.let {
            append("  from ${it.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun renderComment(comment: ReviewComment) {
        val resolved = comment.status == CommentStatus.RESOLVED
        icon = if (resolved) AllIcons.Actions.Checked else AllIcons.General.Balloon
        (comment.anchor.newLine ?: comment.anchor.oldLine)?.let { line ->
            append("L$line  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        val summaryAttributes = if (resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(collapsedSummaryText(comment.body, TREE_SUMMARY_BUDGET), summaryAttributes)
        val replyCount = comment.thread().size
        if (replyCount > 0) {
            append("   ↩ $replyCount", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    // Keep list rendering cheap on the EDT. This badge is only a summary, so we
    // cache line-fragment stats per file revision pair instead of recomputing on every paint.
    private fun estimateLineStats(value: ChangedFile): LineStats {
        return lineStatsCache.getOrPut(value.seenKey()) { computeLineStats(value) }
    }

    private fun computeLineStats(value: ChangedFile): LineStats {
        val before = value.beforeContent?.text.orEmpty()
        val after = value.afterContent?.text.orEmpty()
        if (before.isEmpty() && after.isEmpty()) return LineStats(0, 0)
        if (before.isEmpty()) return LineStats(countLines(after), 0)
        if (after.isEmpty()) return LineStats(0, countLines(before))

        return try {
            val fragments = ComparisonManager.getInstance().compareLines(
                before,
                after,
                ComparisonPolicy.DEFAULT,
                EmptyProgressIndicator(),
            )
            LineStats(
                added = fragments.sumOf { it.endLine2 - it.startLine2 },
                deleted = fragments.sumOf { it.endLine1 - it.startLine1 },
            )
        } catch (_: Throwable) {
            // Fall back to whole-file counts if diff computation fails for any reason.
            LineStats(countLines(after), countLines(before))
        }
    }

    private fun countLines(text: String): Int = if (text.isEmpty()) 0 else text.lines().size

    private fun statusText(status: ChangedFileStatus): String = when (status) {
        ChangedFileStatus.ADDED -> "A"
        ChangedFileStatus.DELETED -> "D"
        ChangedFileStatus.RENAMED -> "R"
        ChangedFileStatus.COPIED -> "C"
        ChangedFileStatus.MODIFIED -> "M"
        ChangedFileStatus.UNKNOWN -> "?"
    }

    private fun statusAttributes(status: ChangedFileStatus): SimpleTextAttributes {
        val color = when (status) {
            ChangedFileStatus.ADDED -> JBColor(0x1A7F37, 0x7EE787)
            ChangedFileStatus.DELETED -> JBColor(0xB42318, 0xFF8E8A)
            else -> JBColor(0x355070, 0xB7D1FF)
        }
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
    }

    private data class LineStats(val added: Int, val deleted: Int)
}
