package com.remind.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetReceiverContractTest {
    @Test fun manifest_receiver_resolves_instantiates_and_points_to_widget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "com.remind.app.widget.RemindWidgetReceiver"
        val info = context.packageManager.getReceiverInfo(ComponentName(context, name), PackageManager.GET_META_DATA)
        assertEquals(name, info.name)
        val instance = Class.forName(info.name).getDeclaredConstructor().newInstance()
        assertTrue(instance is GlanceAppWidgetReceiver)
        assertTrue((instance as GlanceAppWidgetReceiver).glanceAppWidget is RemindWidget)
        assertTrue(info.metaData.getInt("android.appwidget.provider") != 0)
    }
}
