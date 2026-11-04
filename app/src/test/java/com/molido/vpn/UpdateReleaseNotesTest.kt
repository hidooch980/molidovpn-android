package com.molido.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseNotesTest {
    @Test fun extractsReadableBulletsAndSkipsHeadingsAndLinks() {
        val notes = UpdateSources.releaseNotes(
            """
            ## Molido Mesh AI 2.4
            [critical]
            - Improved automatic route recovery
            * Reduced background data use
            • Fixed DNS leak checks
            - https://example.com/details
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "Improved automatic route recovery",
                "Reduced background data use",
                "Fixed DNS leak checks",
            ),
            notes,
        )
    }

    @Test fun limitsNotesAndLineLength() {
        // Unique entries are required because the production parser intentionally de-duplicates notes.
        val markdown = (1..12).joinToString("\n") { "- item $it " + "x".repeat(260) }
        val notes = UpdateSources.releaseNotes(markdown)
        assertEquals(8, notes.size)
        assertTrue(notes.all { it.length <= 240 })
    }

    @Test fun emptyReleaseBodyHasNoInventedNotes() {
        assertTrue(UpdateSources.releaseNotes("## Release\nNo details supplied").isEmpty())
    }
}
