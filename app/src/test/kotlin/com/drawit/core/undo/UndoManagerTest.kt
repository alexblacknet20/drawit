package com.drawit.core.undo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {

    private class Counter {
        var value = 0
    }

    private fun incrementCommand(counter: Counter, amount: Int = 1) = object : Command {
        override val description = "Increment by $amount"
        override fun execute() { counter.value += amount }
        override fun undo() { counter.value -= amount }
    }

    @Test
    fun `execute applies command`() {
        val counter = Counter()
        val undo = UndoManager()
        undo.execute(incrementCommand(counter, 5))
        assertEquals(5, counter.value)
    }

    @Test
    fun `undo reverts command`() {
        val counter = Counter()
        val undo = UndoManager()
        undo.execute(incrementCommand(counter, 5))
        assertTrue(undo.undo())
        assertEquals(0, counter.value)
    }

    @Test
    fun `redo reapplies command`() {
        val counter = Counter()
        val undo = UndoManager()
        undo.execute(incrementCommand(counter, 5))
        undo.undo()
        assertTrue(undo.redo())
        assertEquals(5, counter.value)
    }

    @Test
    fun `new command clears redo stack`() {
        val counter = Counter()
        val undo = UndoManager()
        undo.execute(incrementCommand(counter, 1))
        undo.undo()
        assertTrue(undo.canRedo)
        undo.execute(incrementCommand(counter, 10))
        assertFalse(undo.canRedo)
    }

    @Test
    fun `undo on empty stack returns false`() {
        val undo = UndoManager()
        assertFalse(undo.undo())
    }

    @Test
    fun `stack is bounded by maxDepth`() {
        val counter = Counter()
        val undo = UndoManager(maxDepth = 5)
        repeat(10) { undo.execute(incrementCommand(counter)) }
        assertEquals(10, counter.value)
        // Only 5 undos available
        var undoCount = 0
        while (undo.undo()) undoCount++
        assertEquals(5, undoCount)
        assertEquals(5, counter.value)
    }

    @Test
    fun `mergeable commands coalesce into one undo step`() {
        val counter = Counter()
        val undo = UndoManager()

        // Snapshot-style command: execute applies absolute state (like a drag position).
        // mergeWith combines the new end-state with the original start-state.
        class Drag(val from: Int, val to: Int) : Command {
            override val description = "Drag"
            override fun execute() { counter.value = to }
            override fun undo() { counter.value = from }
            override fun mergeWith(other: Command): Command? =
                (other as? Drag)?.let { Drag(this.from, it.to) }
        }

        undo.execute(Drag(0, 5))   // 0 → 5
        undo.execute(Drag(5, 9))   // merges into Drag(0, 9); execute → 9

        assertEquals(9, counter.value)
        assertTrue(undo.undo())    // single undo reverts the WHOLE drag
        assertEquals(0, counter.value)
        assertFalse(undo.canUndo)  // ...as exactly one undo step
    }
}
