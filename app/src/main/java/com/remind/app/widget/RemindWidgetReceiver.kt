package com.remind.app.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Kept in its own file so UI-only rewrites cannot remove the Android entry point. */
class RemindWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = RemindWidget()
}
