package dev.fatihdogmus.agenticreview.testutil

import com.intellij.ui.treeStructure.Tree
import dev.fatihdogmus.agenticreview.ui.ChangedFilesPanel
import dev.fatihdogmus.agenticreview.ui.ReviewToolWindowPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree

fun findComponents(root: Component): List<Component> = buildList {
    add(root)
    if (root is Container) {
        root.components.forEach { child -> addAll(findComponents(child)) }
    }
}

fun findLabel(component: Component, text: String): JLabel? {
    if (component is JLabel && component.text == text) return component
    if (component is JComponent) {
        component.components.forEach { child ->
            findLabel(child, text)?.let { return it }
        }
    }
    return null
}

fun labels(component: Component): List<JLabel> = findComponents(component).filterIsInstance<JLabel>()

fun reviewTree(panel: ChangedFilesPanel): Tree =
    findComponents(panel.component).filterIsInstance<Tree>().single()

fun turnCombo(panel: ChangedFilesPanel): JComboBox<*> =
    findComponents(panel.component)
        .filterIsInstance<JComboBox<*>>()
        .single { combo -> combo.itemCount > 0 && combo.getItemAt(0).toString() == "Review Changes" }

fun turnCombo(panel: ReviewToolWindowPanel): JComboBox<*> =
    findComponents(panel)
        .filterIsInstance<JComboBox<*>>()
        .single { combo -> combo.itemCount > 0 && combo.getItemAt(0).toString() == "Review Changes" }

@Suppress("UNCHECKED_CAST")
fun reviewSelector(panel: ReviewToolWindowPanel): JComboBox<*> =
    findComponents(panel)
        .filterIsInstance<JComboBox<*>>()
        .first { combo -> combo.itemCount > 0 && combo.getItemAt(0).toString() != "Review Changes" }

fun titleLabel(panel: ChangedFilesPanel): JLabel =
    findComponents(panel.component)
        .filterIsInstance<JLabel>()
        .first { it.text == "Changed Files" || it.text == "Turn Changed Files" }
