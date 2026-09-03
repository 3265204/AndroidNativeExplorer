package com.ane.filemanager.ui.onboarding

import com.ane.filemanager.ui.onboarding.CoachMarkPlacement.Box
import com.ane.filemanager.ui.onboarding.CoachMarkPlacement.VerticalPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachMarkPlacementTest {
    @Test fun `bottom popup moves above a folder it would cover`() {
        val folder = Box(0f, 650f, 400f, 790f)

        val popup = place(contentAvoid = listOf(folder), preference = VerticalPreference.BOTTOM)

        assertTrue(popup.bottom <= folder.top - 8f)
        assertEquals(0f, popup.intersectionArea(folder), 0f)
    }

    @Test fun `layout chooser stays at the bottom when folders are at the top`() {
        val folders = listOf(
            Box(8f, 80f, 192f, 210f),
            Box(208f, 80f, 392f, 210f)
        )

        val popup = place(
            popupHeight = 286f,
            contentAvoid = folders,
            preference = VerticalPreference.BOTTOM
        )

        assertEquals(498f, popup.top, 0f)
        assertTrue(folders.all { popup.intersectionArea(it) == 0f })
    }

    @Test fun `the highlighted target takes priority over other content`() {
        val target = Box(0f, 650f, 400f, 790f)
        val upperFolder = Box(0f, 16f, 400f, 150f)

        val popup = place(
            priorityAvoid = listOf(target),
            contentAvoid = listOf(upperFolder),
            preference = VerticalPreference.BOTTOM
        )

        assertEquals(0f, popup.intersectionArea(target), 0f)
        assertEquals(0f, popup.intersectionArea(upperFolder), 0f)
    }

    private fun place(
        popupHeight: Float = 126f,
        priorityAvoid: List<Box> = emptyList(),
        contentAvoid: List<Box> = emptyList(),
        preference: VerticalPreference
    ) = CoachMarkPlacement.place(
        viewportWidth = 400f,
        viewportHeight = 800f,
        popupWidth = 368f,
        popupHeight = popupHeight,
        margin = 16f,
        gap = 8f,
        priorityAvoid = priorityAvoid,
        contentAvoid = contentAvoid,
        preference = preference
    )
}
