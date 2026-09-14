package dev.fatihdogmus.agenticreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.fatihdogmus.agenticreview.ReviewManagerService
import dev.fatihdogmus.agenticreview.diff.ReviewDiffRequestData
import dev.fatihdogmus.agenticreview.model.CommentReply
import dev.fatihdogmus.agenticreview.model.ReplyKind
import dev.fatihdogmus.agenticreview.model.ReviewComment
import dev.fatihdogmus.agenticreview.model.thread
import java.awt.*
import java.awt.event.*
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.AbstractBorder

private const val PLACEHOLDER_TEXT = "Add comment"
private const val COMMENT_ACTIONS_PLACE = "AgenticReview.CommentActions"
private const val COMMENT_CARD_ARC = 18
private const val COMMENT_PANEL_ARC = 20
private val BLUE_BORDER =
    UIManager.getColor("Component.focusColor") ?: JBColor(Color(0x35, 0x7A, 0xB8), Color(0x6C, 0xB8, 0xFF))
private val COMMENT_BORDER = UIManager.getColor("Component.borderColor") ?: JBColor.border()
private val DELETE_RED = JBColor(Color(0xC4, 0x2B, 0x1C), Color(0xFF, 0x8E, 0x8A))
private val MENU_HOVER_BG =
    UIManager.getColor("List.selectionBackground") ?: JBColor(Color(0xE8, 0xF1, 0xFF), Color(0x2C, 0x3D, 0x55))
private val COMMENT_SURFACE_BG =
    JBColor(Color(0xF9, 0xFA, 0xFC), Color(0x22262B))
private val COMMENT_FORM_BG =
    JBColor(Color(0xFB, 0xFC, 0xFF), Color(0x1F242A))
private val INPUT_BG =
    UIManager.getColor("TextArea.background") ?: JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x1B, 0x1F, 0x24))
private val SUBTLE_TEXT =
    UIManager.getColor("Label.infoForeground") ?: JBColor(Color(0x6B, 0x72, 0x80), Color(0x98, 0xA1, 0xAE))
private val PRIMARY_BUTTON_BG = JBColor(Color(0x2E, 0x6B, 0xD9), Color(0x3D, 0x7A, 0xED))
private val PRIMARY_BUTTON_BORDER = JBColor(Color(0x2A, 0x60, 0xC2), Color(0x5D, 0x95, 0xF5))
private val PRIMARY_BUTTON_FG = JBColor(Color.WHITE, Color.WHITE)
private val SECONDARY_BUTTON_BG = JBColor(Color(0xF5, 0xF7, 0xFA), Color(0x26, 0x2B, 0x31))
private val COMMENT_PILL_BG = JBColor(Color(0xEC, 0xF2, 0xFF), Color(0x27364A))
private val COMMENT_PILL_FG = JBColor(Color(0x244A7A), Color(0xD6E5FF))

fun showReviewCommentInlays(
    project: Project,
    editor: EditorEx,
    requestData: ReviewDiffRequestData,
): List<Inlay<*>> {
    val manager = ReviewManagerService.getInstance(project)
    return manager.commentsForFile(requestData.reviewId, requestData.changedFile.filePath)
        .mapNotNull { comment ->
            val line = (comment.anchor.newLine ?: comment.anchor.oldLine)?.minus(1) ?: return@mapNotNull null
            showExistingCommentInlay(project, editor, comment, line)
        }
}

fun showInlineCommentForm(
    project: Project,
    editor: EditorEx,
    startLine: Int,
    requestData: ReviewDiffRequestData,
    endLine: Int? = null,
) {
    val form = InlineCommentFormPanel(project, editor, startLine, requestData, endLine)
    val inlay = insertInlineComponent(editor, startLine, form)

    form.inlayRef = inlay
    form.requestTextFocus()
}

@Suppress("UnstableApiUsage")
private fun insertInlineComponent(editor: EditorEx, line: Int, component: JPanel): Inlay<*>? {
    val safeLine = line.coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
    val offset =
        if (editor.document.lineCount > 0) editor.document.getLineEndOffset(safeLine) else editor.document.textLength
    return editor.addComponentInlay(
        offset,
        InlayProperties()
            .relatesToPrecedingText(true)
            .priority(0),
        component,
        ComponentInlayAlignment.FIT_VIEWPORT_WIDTH,
    )
}

private class InlineCommentFormPanel(
    private val project: Project,
    private val editor: EditorEx,
    private val startLine: Int,
    private val requestData: ReviewDiffRequestData,
    private val endLine: Int?,
) : JPanel(BorderLayout(0, 6)) {

    var inlayRef: Inlay<*>? = null
    private val textArea: JBTextArea

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 16, 10, 16)

        textArea = JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(10, 12)
            foreground = JBColor.GRAY
            text = PLACEHOLDER_TEXT
            background = INPUT_BG
            caretColor = editor.colorsScheme.defaultForeground
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            font = font.deriveFont(font.size2D + 1f)

            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent?) {
                    if (text == PLACEHOLDER_TEXT) {
                        text = ""
                        foreground = editor.colorsScheme.defaultForeground
                    }
                }

                override fun focusLost(e: FocusEvent?) {
                    if (text.isBlank()) {
                        text = PLACEHOLDER_TEXT
                        foreground = JBColor.GRAY
                    }
                }
            })
        }

        val scrollPane = createCommentInputScrollPane(textArea)

        val hintText = if (endLine != null && endLine > startLine) {
            "Lines ${startLine + 1}-${endLine + 1}"
        } else {
            "Line ${startLine + 1}"
        }
        val titleLabel = JLabel("New comment").apply {
            foreground = editor.colorsScheme.defaultForeground
            font = font.deriveFont(font.style or Font.BOLD)
        }
        val hintLabel = JLabel("${submitShortcutText()} to submit").apply {
            foreground = SUBTLE_TEXT
        }
        val linePill = createPillLabel(hintText)

        val cancelButton = JButton("Cancel").apply {
            applyCommentActionStyle(primary = false)
            addActionListener { dismiss() }
        }
        val commentButton = JButton("Comment").apply {
            applyCommentActionStyle(primary = true)
            addActionListener {
                val body = textArea.text.trim()
                if (body.isNotEmpty() && body != PLACEHOLDER_TEXT) {
                    val comment = ReviewManagerService.getInstance(project).addComment(
                        requestData.reviewId,
                        requestData.changedFile,
                        requestData.commentSide,
                        startLine + 1,
                        body,
                        endLineNumber = endLine?.let { it + 1 },
                    )
                    dismiss()
                    if (comment != null) {
                        showExistingCommentInlay(project, editor, comment, startLine)
                    }
                } else {
                    dismiss()
                }
            }
        }
        installSubmitShortcut(textArea) { commentButton.doClick() }

        val header = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(titleLabel)
                add(linePill)
            }, BorderLayout.WEST)
            add(hintLabel, BorderLayout.EAST)
        }

        val bottomPanel = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(JLabel("Comments are attached to this diff line.").apply {
                foreground = SUBTLE_TEXT
            }, BorderLayout.WEST)
            val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(cancelButton)
                add(commentButton)
            }
            add(buttonsPanel, BorderLayout.EAST)
        }

        val card = RoundedSurfacePanel(COMMENT_FORM_BG, COMMENT_BORDER, COMMENT_PANEL_ARC).apply {
            layout = BorderLayout(0, JBUI.scale(10))
            border = JBUI.Borders.empty(12, 14)
            add(header, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }

        add(card, BorderLayout.CENTER)
    }

    fun requestTextFocus() {
        textArea.requestFocusInWindow()
    }

    private fun dismiss() {
        inlayRef?.let { Disposer.dispose(it) }
    }
}

private fun showExistingCommentInlay(
    project: Project,
    editor: EditorEx,
    comment: ReviewComment,
    line: Int,
): Inlay<*>? {
    val panel = ExistingCommentPanel(project, editor, comment)
    val inlay = insertInlineComponent(editor, line, panel)
    panel.inlayRef = inlay
    return inlay
}

private class ExistingCommentPanel(
    private val project: Project,
    private val editor: EditorEx,
    private val comment: ReviewComment,
) : JPanel(BorderLayout(0, 6)) {

    var inlayRef: Inlay<*>? = null
    private val normalBackground = editor.colorsScheme.defaultBackground
    private var editing = false
    private val menuButton: JButton
    private val body: JBTextArea
    private val editActionsPanel: JPanel
    private val repliesPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val replyTextArea = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8, 10)
        background = INPUT_BG
    }
    private lateinit var replyFormPanel: JPanel
    private lateinit var replyActionsPanel: JPanel

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 16, 10, 16)

        val line = comment.anchor.newLine ?: comment.anchor.oldLine ?: 0
        val endLine = comment.anchor.endNewLine ?: comment.anchor.endOldLine
        val lineLabel = if (endLine != null && endLine > line) "Lines $line-$endLine" else "Line $line"

        menuButton = JButton().apply {
            icon = AllIcons.Actions.More
            text = null
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            preferredSize = JBUI.size(28, 28)
            border = JBUI.Borders.empty(4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Comment actions"
            isVisible = true
            putClientProperty("JButton.buttonType", "toolbar")
            addActionListener {
                val group = DefaultActionGroup().apply {
                    add(object : AnAction(
                        "Edit comment",
                        null,
                        IconUtil.colorize(AllIcons.Actions.Edit, BLUE_BORDER, false, false)
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            enterEditMode()
                        }
                    })
                    add(object : AnAction(
                        "Resolve comment",
                        null,
                        IconUtil.colorize(AllIcons.Actions.Checked, BLUE_BORDER, false, false)
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            resolveCommentAndDismiss(project, comment.id, ::dismiss)
                        }
                    })
                    add(object : AnAction(
                        "Delete comment",
                        null,
                        IconUtil.colorize(AllIcons.General.Remove, DELETE_RED, false, false)
                    ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            deleteCommentAndDismiss(project, comment.id, ::dismiss)
                        }
                    })
                }
                val menu = ActionManager.getInstance().createActionPopupMenu(COMMENT_ACTIONS_PLACE, group).component
                menu.border = JBUI.Borders.compound(
                    BorderFactory.createLineBorder(COMMENT_BORDER, 1),
                    JBUI.Borders.empty(4)
                )
                menu.show(this, width - menu.preferredSize.width, height)
                SwingUtilities.invokeLater { stylePopupMenu(menu) }
            }
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(createPillLabel(lineLabel))
                add(JLabel("Open").apply { foreground = SUBTLE_TEXT })
            }, BorderLayout.WEST)
            add(menuButton, BorderLayout.EAST)
        }

        body = JBTextArea(comment.body).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(2, 0)
            background = COMMENT_SURFACE_BG
            foreground = editor.colorsScheme.defaultForeground
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            font = font.deriveFont(font.size2D + 1f)
        }

        val saveButton = JButton("Save").apply {
            applyCommentActionStyle(primary = true)
            addActionListener {
                val updatedBody = body.text.trim()
                if (updatedBody.isNotEmpty()) {
                    ReviewManagerService.getInstance(project).updateComment(comment.id, updatedBody)
                    body.text = comment.body
                }
                exitEditMode()
            }
        }
        installSubmitShortcut(body) { saveButton.doClick() }
        val cancelButton = JButton("Cancel").apply {
            applyCommentActionStyle(primary = false)
            addActionListener {
                body.text = comment.body
                exitEditMode()
            }
        }
        editActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            isVisible = false
            add(cancelButton)
            add(saveButton)
        }

        val postReplyButton = JButton("Post reply").apply {
            applyCommentActionStyle(primary = true)
            addActionListener { submitReply() }
        }
        installSubmitShortcut(replyTextArea) { postReplyButton.doClick() }
        val cancelReplyButton = JButton("Cancel reply").apply {
            applyCommentActionStyle(primary = false)
            addActionListener { hideReplyForm() }
        }
        replyFormPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
            add(createCommentInputScrollPane(replyTextArea), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(cancelReplyButton)
                add(postReplyButton)
            }, BorderLayout.SOUTH)
        }
        val replyButton = JButton("Reply").apply {
            applyCommentActionStyle(primary = false)
            addActionListener { showReplyForm() }
        }
        replyActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(replyButton)
        }

        val centerPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(body.apply { alignmentX = LEFT_ALIGNMENT })
            add(repliesPanel)
            add(replyFormPanel)
        }
        val southPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(editActionsPanel.apply { alignmentX = LEFT_ALIGNMENT })
            add(replyActionsPanel)
        }

        val card = RoundedSurfacePanel(COMMENT_SURFACE_BG, COMMENT_BORDER, COMMENT_PANEL_ARC).apply {
            layout = BorderLayout(0, JBUI.scale(10))
            border = JBUI.Borders.empty(10, 14)
            add(header, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
            add(southPanel, BorderLayout.SOUTH)
        }

        add(card, BorderLayout.CENTER)
        renderReplies()
    }

    private fun renderReplies() {
        repliesPanel.removeAll()
        comment.thread().forEach { reply ->
            repliesPanel.add(createReplyBlock(reply, editor))
        }
    }

    private fun showReplyForm() {
        if (!replyFormPanel.isVisible) {
            replyTextArea.text = ""
            replyFormPanel.isVisible = true
            refreshLayout()
        }
        replyTextArea.requestFocusInWindow()
    }

    private fun hideReplyForm() {
        replyFormPanel.isVisible = false
        refreshLayout()
    }

    private fun submitReply() {
        val text = replyTextArea.text.trim()
        if (text.isNotEmpty()) {
            ReviewManagerService.getInstance(project).addReply(comment.id, text)
            renderReplies()
        }
        hideReplyForm()
    }

    private fun refreshLayout() {
        revalidate()
        repaint()
        inlayRef?.update()
    }

    private fun enterEditMode() {
        editing = true
        body.isEditable = true
        body.border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(BLUE_BORDER, COMMENT_CARD_ARC),
            JBUI.Borders.empty(10, 12),
        )
        editActionsPanel.isVisible = true
        body.background = INPUT_BG
        body.requestFocusInWindow()
        body.caretPosition = body.text.length
        revalidate()
    }

    private fun exitEditMode() {
        editing = false
        body.isEditable = false
        body.border = JBUI.Borders.empty(2, 0)
        editActionsPanel.isVisible = false
        body.background = COMMENT_SURFACE_BG
        revalidate()
    }

    private fun dismiss() {
        inlayRef?.dispose()
        inlayRef = null
    }

    private fun stylePopupMenu(menu: JPopupMenu) {
        val defaultBg = UIManager.getColor("PopupMenu.background") ?: menu.background
        menu.subElements.mapNotNull { it.component as? JComponent }.forEach { component ->
            component.isOpaque = true
            component.background = defaultBg
            if (component is JMenuItem) {
                component.border = JBUI.Borders.empty(6, 10)
                component.iconTextGap = 8
                component.foreground = UIManager.getColor("MenuItem.foreground") ?: component.foreground
                if (component.text == "Delete comment") {
                    component.foreground = DELETE_RED
                }
            }
            component.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    component.background = MENU_HOVER_BG
                }

                override fun mouseExited(e: MouseEvent) {
                    component.background = defaultBg
                }
            })
        }
    }
}

private fun createCommentInputScrollPane(textArea: JBTextArea): JScrollPane = JScrollPane(textArea).apply {
    border = RoundedLineBorder(BLUE_BORDER, COMMENT_CARD_ARC)
    preferredSize = Dimension(JBUI.scale(400), JBUI.scale(96))
    background = INPUT_BG
    isOpaque = true
    viewport.background = INPUT_BG
    viewport.isOpaque = true
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
}

private val REPLY_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Renders a stored ISO-8601 [CommentReply.createdAt] as `yyyy-MM-dd HH:mm:ss` for display.
 *
 * The recorded wall-clock time is kept as-is rather than converted to the JVM's default zone, so the
 * rendered value does not depend on where the IDE happens to be running. Unparseable input is
 * returned unchanged, so a hand-edited archive degrades to showing the raw string instead of
 * breaking the inlay.
 */
internal fun formatReplyTimestamp(raw: String): String =
    runCatching { OffsetDateTime.parse(raw).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(raw) }
        .map { it.format(REPLY_TIMESTAMP_FORMAT) }
        .getOrDefault(raw)

private fun createReplyBlock(reply: CommentReply, editor: EditorEx): JPanel {
    val header = JLabel("${reply.author} · ${formatReplyTimestamp(reply.createdAt)}").apply {
        foreground = SUBTLE_TEXT
        if (reply.kind == ReplyKind.RESOLUTION) {
            icon = IconUtil.colorize(AllIcons.Actions.Checked, BLUE_BORDER, false, false)
        }
    }
    val bodyArea = JBTextArea(reply.body).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(2, 0)
        background = COMMENT_SURFACE_BG
        foreground = editor.colorsScheme.defaultForeground
    }
    return JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        alignmentX = JPanel.LEFT_ALIGNMENT
        border = JBUI.Borders.compound(
            JBUI.Borders.emptyTop(8),
            JBUI.Borders.compound(
                JBUI.Borders.customLine(COMMENT_BORDER, 0, 2, 0, 0),
                JBUI.Borders.emptyLeft(10),
            ),
        )
        add(header, BorderLayout.NORTH)
        add(bodyArea, BorderLayout.CENTER)
    }
}

private fun createPillLabel(text: String): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
    isOpaque = false
    add(PillLabel(text))
}

private fun JButton.applyCommentActionStyle(primary: Boolean): JButton = apply {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    foreground = if (primary) PRIMARY_BUTTON_FG else UIManager.getColor("Button.foreground") ?: JBColor.foreground()
    background = if (primary) PRIMARY_BUTTON_BG else SECONDARY_BUTTON_BG
    border = JBUI.Borders.empty(7, 14)
    isOpaque = false
    isContentAreaFilled = false
    isFocusPainted = false
    putClientProperty(
        COMMENT_BUTTON_BORDER_COLOR,
        if (primary) PRIMARY_BUTTON_BORDER else COMMENT_BORDER,
    )
}

private fun submitShortcutText(): String = if (System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true) {
    "Cmd+Enter"
} else {
    "Ctrl+Enter"
}

private const val COMMENT_BUTTON_BORDER_COLOR = "local.review.commentButtonBorderColor"

private class RoundedSurfacePanel(
    private val fill: Color,
    private val stroke: Color,
    private val arc: Int,
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = stroke
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class PillLabel(text: String) : JLabel(text) {
    private val arc = JBUI.scale(COMMENT_CARD_ARC)

    init {
        isOpaque = false
        foreground = COMMENT_PILL_FG
        border = JBUI.Borders.empty(3, 8)
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = COMMENT_PILL_BG
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = COMMENT_BORDER
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(graphics)
    }
}

private class RoundedLineBorder(
    private val color: Color,
    private val arc: Int,
) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(c: Component?) = Insets(1, 1, 1, 1)

    override fun getBorderInsets(c: Component?, insets: Insets): Insets {
        insets.set(1, 1, 1, 1)
        return insets
    }
}

internal fun resolveCommentAndDismiss(project: Project, commentId: String, dismiss: () -> Unit): Boolean {
    val changed = ReviewManagerService.getInstance(project).markCommentResolved(commentId)
    if (changed) dismiss()
    return changed
}

internal fun deleteCommentAndDismiss(project: Project, commentId: String, dismiss: () -> Unit): Boolean {
    val changed = ReviewManagerService.getInstance(project).deleteComment(commentId)
    if (changed) dismiss()
    return changed
}

private fun installSubmitShortcut(textArea: JBTextArea, submit: () -> Unit) {
    val actionKey = "localReview.submitComment"
    textArea.inputMap.put(commentSubmitKeyStroke(), actionKey)
    textArea.actionMap.put(actionKey, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) {
            submit()
        }
    })
}

private fun commentSubmitKeyStroke(): KeyStroke = KeyStroke.getKeyStroke(
    KeyEvent.VK_ENTER,
    if (GraphicsEnvironment.isHeadless()) InputEvent.CTRL_DOWN_MASK else Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
)
