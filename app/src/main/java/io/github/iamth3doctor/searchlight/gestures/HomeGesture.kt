package io.github.iamth3doctor.searchlight.gestures

import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Swipe

/**
 * The gestures on empty home space, each running the action chosen in Settings. The defaults are
 * what each did before it could be changed. Saved by name, so a choice survives restarts and updates.
 */
enum class HomeGesture(val direction: Swipe.Direction?, val title: Int, val default: GestureAction) {
    SWIPE_UP(Swipe.Direction.UP, R.string.gesture_swipe_up, GestureAction.APP_DRAWER),
    SWIPE_DOWN(Swipe.Direction.DOWN, R.string.gesture_swipe_down, GestureAction.NOTIFICATIONS),
    SWIPE_LEFT(Swipe.Direction.LEFT, R.string.gesture_swipe_left, GestureAction.APP_DRAWER),
    SWIPE_RIGHT(Swipe.Direction.RIGHT, R.string.gesture_swipe_right, GestureAction.SEARCH),
    DOUBLE_TAP(null, R.string.gesture_double_tap, GestureAction.LOCK_SCREEN),
    TRIPLE_TAP(null, R.string.gesture_triple_tap, GestureAction.TOP_SIX),
    LONG_PRESS(null, R.string.gesture_long_press, GestureAction.WIDGET_CENTER);

    val isSwipe: Boolean get() = direction != null

    companion object {
        fun of(direction: Swipe.Direction): HomeGesture? = entries.firstOrNull { it.direction == direction }
    }
}

/** What a gesture does. The system-UI ones go through [GestureService] (accessibility). */
enum class GestureAction(val label: Int, val needsAccessibility: Boolean = false) {
    NONE(R.string.gesture_action_none),
    TOP_SIX(R.string.gesture_action_top_six),
    SEARCH(R.string.gesture_action_search),
    LOCK_SCREEN(R.string.gesture_action_lock_screen, needsAccessibility = true),
    APP_DRAWER(R.string.gesture_action_app_drawer),
    WEATHER(R.string.gesture_action_weather),
    CALENDAR(R.string.gesture_action_calendar),
    FOCUS(R.string.gesture_action_focus),
    NOTIFICATIONS(R.string.gesture_action_notifications, needsAccessibility = true),
    QUICK_SETTINGS(R.string.gesture_action_quick_settings, needsAccessibility = true),
    WIDGET_CENTER(R.string.gesture_action_widget_center),
    LAUNCH_APP(R.string.gesture_action_launch_app),
}
