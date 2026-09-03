package com.ane.filemanager.ui.onboarding

import com.ane.filemanager.ui.onboarding.TutorialProgress.Action
import com.ane.filemanager.ui.onboarding.TutorialProgress.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialProgressTest {
    @Test fun `virtual file workflow advances in the required order`() {
        val progress = TutorialProgress()

        assertEquals(Step.LAYOUT, progress.step)
        assertTrue(progress.accept(Action.CHOOSE_LAYOUT))
        assertEquals(Step.SELECT, progress.step)
        assertTrue(progress.accept(Action.TAP_ITEM))
        assertEquals(Step.MOVE_TO_DOCK, progress.step)
        assertTrue(progress.accept(Action.MOVE_TO_DOCK))
        assertEquals(Step.OPEN_MOVE_DESTINATION, progress.step)
        assertTrue(progress.accept(Action.SWITCH_TO_MOVED_FILE))
        assertEquals(Step.LONG_PRESS_MENU, progress.step)
        assertTrue(progress.accept(Action.LONG_PRESS_MENU))
        assertEquals(Step.COPY_CHOOSE, progress.step)
        assertTrue(progress.accept(Action.COPY))
        assertEquals(Step.OPEN_COPY_DESTINATION, progress.step)
        assertTrue(progress.accept(Action.SWITCH_TO_COPY_DESTINATION))
        assertEquals(Step.PASTE_OPEN_MENU, progress.step)
        assertTrue(progress.accept(Action.OPEN_MENU))
        assertEquals(Step.PASTE_CHOOSE, progress.step)
        assertTrue(progress.accept(Action.PASTE))
        assertEquals(Step.OPEN, progress.step)
        assertTrue(progress.accept(Action.DOUBLE_TAP_ITEM))
        assertEquals(Step.TABS, progress.step)
        assertTrue(progress.accept(Action.SWITCH_TAB))
        assertEquals(Step.COMPLETE, progress.step)
    }

    @Test fun `out of order actions cannot skip a real operation`() {
        val progress = TutorialProgress()

        assertFalse(progress.accept(Action.MOVE_TO_DOCK))
        assertFalse(progress.accept(Action.COPY))
        assertFalse(progress.accept(Action.PASTE))
        assertFalse(progress.accept(Action.SWITCH_TAB))
        assertEquals(Step.LAYOUT, progress.step)
    }

    @Test fun `copy requires the moved file long press menu first`() {
        val progress = TutorialProgress()
        progress.accept(Action.CHOOSE_LAYOUT)
        progress.accept(Action.TAP_ITEM)
        progress.accept(Action.MOVE_TO_DOCK)
        progress.accept(Action.SWITCH_TO_MOVED_FILE)

        assertFalse(progress.accept(Action.COPY))
        assertEquals(Step.LONG_PRESS_MENU, progress.step)
        progress.accept(Action.LONG_PRESS_MENU)
        assertTrue(progress.accept(Action.COPY))
        assertEquals(Step.OPEN_COPY_DESTINATION, progress.step)
    }

    @Test fun `file practice cannot begin until a layout is chosen`() {
        val progress = TutorialProgress()

        assertFalse(progress.accept(Action.TAP_ITEM))
        assertEquals(Step.LAYOUT, progress.step)
        assertTrue(progress.accept(Action.CHOOSE_LAYOUT))
        assertEquals(Step.SELECT, progress.step)
    }

}
