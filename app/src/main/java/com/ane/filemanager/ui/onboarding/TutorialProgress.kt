package com.ane.filemanager.ui.onboarding

/** Small, platform-free state machine so the mandatory practice flow is easy to verify. */
internal class TutorialProgress {
    enum class Step {
        SELECT,
        MOVE_TO_DOCK,
        OPEN_MOVE_DESTINATION,
        LONG_PRESS_MENU,
        COPY_CHOOSE,
        OPEN_COPY_DESTINATION,
        PASTE_OPEN_MENU,
        PASTE_CHOOSE,
        OPEN,
        TABS,
        COMPLETE
    }
    enum class Action {
        TAP_ITEM,
        MOVE_TO_DOCK,
        SWITCH_TO_MOVED_FILE,
        LONG_PRESS_MENU,
        OPEN_MENU,
        COPY,
        SWITCH_TO_COPY_DESTINATION,
        PASTE,
        DOUBLE_TAP_ITEM,
        SWITCH_TAB
    }

    var step: Step = Step.SELECT
        private set

    fun accept(action: Action): Boolean {
        val previousStep = step
        when (step) {
            Step.SELECT -> if (action == Action.TAP_ITEM) step = Step.MOVE_TO_DOCK
            Step.MOVE_TO_DOCK -> if (action == Action.MOVE_TO_DOCK) step = Step.OPEN_MOVE_DESTINATION
            Step.OPEN_MOVE_DESTINATION -> if (action == Action.SWITCH_TO_MOVED_FILE) step = Step.LONG_PRESS_MENU
            Step.LONG_PRESS_MENU -> if (action == Action.LONG_PRESS_MENU) step = Step.COPY_CHOOSE
            Step.COPY_CHOOSE -> if (action == Action.COPY) step = Step.OPEN_COPY_DESTINATION
            Step.OPEN_COPY_DESTINATION -> if (action == Action.SWITCH_TO_COPY_DESTINATION) {
                step = Step.PASTE_OPEN_MENU
            }
            Step.PASTE_OPEN_MENU -> if (action == Action.OPEN_MENU) step = Step.PASTE_CHOOSE
            Step.PASTE_CHOOSE -> if (action == Action.PASTE) step = Step.OPEN
            Step.OPEN -> if (action == Action.DOUBLE_TAP_ITEM) step = Step.TABS
            Step.TABS -> if (action == Action.SWITCH_TAB) step = Step.COMPLETE
            Step.COMPLETE -> Unit
        }
        return step != previousStep
    }

}
