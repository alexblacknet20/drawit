package com.drawit.core.undo

/**
 * A reversible edit to the document.
 */
interface Command {
    val description: String
    fun execute()
    fun undo()
    /** Optional: merge with a subsequent command (e.g., continuous drag). */
    fun mergeWith(other: Command): Command? = null
}

/**
 * Undo/redo stack with coalescing and memory bounds.
 */
class UndoManager(
    private val maxDepth: Int = 100
) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    var onStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val undoDescription: String? get() = undoStack.lastOrNull()?.description
    val redoDescription: String? get() = redoStack.lastOrNull()?.description

    /** Execute a command and push it onto the undo stack. */
    fun execute(command: Command) {
        // Try to merge with the previous command (e.g., continuous slider drags)
        val previous = undoStack.lastOrNull()
        val merged = previous?.mergeWith(command)

        if (merged != null) {
            undoStack.removeLast()
            merged.execute()
            undoStack.addLast(merged)
        } else {
            command.execute()
            undoStack.addLast(command)
        }

        // Bound memory
        while (undoStack.size > maxDepth) {
            undoStack.removeFirst()
        }

        // Any new edit invalidates the redo stack
        redoStack.clear()
        notifyChanged()
    }

    fun undo(): Boolean {
        val command = undoStack.removeLastOrNull() ?: return false
        command.undo()
        redoStack.addLast(command)
        notifyChanged()
        return true
    }

    fun redo(): Boolean {
        val command = redoStack.removeLastOrNull() ?: return false
        command.execute()
        undoStack.addLast(command)
        notifyChanged()
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        notifyChanged()
    }

    private fun notifyChanged() {
        onStateChanged?.invoke(canUndo, canRedo)
    }
}

/**
 * Helper: a command that swaps document state between two snapshots.
 * Simple but memory-heavy; Phase 2+ will use delta commands for big edits.
 */
class SnapshotCommand(
    override val description: String,
    private val before: Any,
    private val after: Any,
    private val apply: (Any) -> Unit
) : Command {
    override fun execute() = apply(after)
    override fun undo() = apply(before)
}
