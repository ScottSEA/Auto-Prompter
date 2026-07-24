package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.SaveOutcome
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals

private fun doc(id: String, title: String, body: String): ScriptDocument =
    ScriptDocument(
        id = DocumentId(id),
        title = title,
        blocks = listOf(ScriptBlock(BlockId("b0"), ScriptBlockKind.Paragraph, body)),
    )

/**
 * The contract's concurrency behavior is proven again here across **two independently opened store
 * instances on the same database**, not just two coroutines sharing one instance. IndexedDB
 * serializes overlapping readwrite transactions on an object store across every open connection, so
 * two saves with the same precondition -- issued through separate [IndexedDbDocumentStore] objects --
 * still resolve to exactly one winner and one conflict. This is the guarantee two browser tabs rely
 * on.
 */
class IndexedDbDocumentStoreConcurrencyTest {

    @Test
    fun twoIndependentInstancesOnSameDatabaseSatisfyCompareAndSet() = webScopeTest { scope ->
        val name = scope.newDatabaseName()
        val id = DocumentId("doc-1")

        // Seed a base revision (gen 1) through one connection, then close it.
        val seeder = scope.openStore(name)
        seeder.save(doc("doc-1", "Base", "Base body."), SavePrecondition.MustBeMissing)
        seeder.close()

        // Two independently opened connections to the SAME database race the same precondition.
        val left = scope.openStore(name)
        val right = scope.openStore(name)

        val outcomes = coroutineScope {
            val a = async {
                left.save(doc("doc-1", "Left", "Left body."), SavePrecondition.Matches(StoreGeneration(1)))
            }
            val b = async {
                right.save(doc("doc-1", "Right", "Right body."), SavePrecondition.Matches(StoreGeneration(1)))
            }
            listOf(a.await(), b.await())
        }

        val saved = outcomes.filterIsInstance<SaveOutcome.Saved>()
        val conflicts = outcomes.filterIsInstance<SaveOutcome.Conflict>()
        assertEquals(1, saved.size, "Exactly one cross-connection save must win.")
        assertEquals(1, conflicts.size, "Exactly one cross-connection save must conflict.")

        val winner = saved.single().snapshot
        assertEquals(StoreGeneration(2), winner.generation)

        // Both connections observe the single winning revision after the race.
        assertEquals(winner, left.load(id))
        assertEquals(winner, right.load(id))
        assertEquals(DocumentState.Live(winner), conflicts.single().current)
    }
}
