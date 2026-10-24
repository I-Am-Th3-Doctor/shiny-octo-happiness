package io.github.iamth3doctor.searchlight.gestures

import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Swipe

/** The three configurable swipes on empty home space (up always opens the app table). */
enum class HomeGesture(val direction: Swipe.Direction, val title: Int, val default: GestureAction) {
    SWIPE_DOWN(Swipe.Direction.DOWN, R.string.gesture_swipe_down, GestureAction.NOTIFICATIONS),
    SWIPE_LEFT(Swipe.Direction.LEFT, R.string.gesture_swipe_left, GestureAction.APP_DRAWER),
    SWIPE_RIGHT(Swipe.Direction.RIGHT, R.string.gesture_swipe_right, GestureAction.SEARCH);

    companion object {
        fun of(direction: Swipe.Direction): HomeGesture? = entries.firstOrNull { it.direction == direction }
    }
}

/** What a gesture does. The system-UI ones go through [GestureService] (accessibility). */
enum class GestureAction(val label: Int, val needsAccessibility: Boolean = false) {
    NONE(R.string.gesture_action_none),
    NOTIFICATIONS(R.string.gesture_action_notifications, needsAccessibility = true),
    QUICK_SETTINGS(R.string.gesture_action_quick_settings, needsAccessibility = true),
    APP_DRAWER(R.string.gesture_action_app_drawer),
    SEARCH(R.string.gesture_action_search),
    WIDGET_CENTER(R.string.gesture_action_widget_center),
    LOCK_SCREEN(R.string.gesture_action_lock_screen, needsAccessibility = true),
    LAUNCH_APP(R.string.gesture_action_launch_app),
}
