package com.remind.app.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Packaging contract guards for defects that unit/render tests cannot see but the
 * shipped APK proved real:
 *  - v0.2: widget receiver shipped without appwidget.provider meta-data, so Remind
 *    never appeared in launcher widget pickers.
 *  - v0.2/v0.2.1: no INTERNET permission, so Google backup failed on device with
 *    "Permission denied (missing INTERNET permission?)".
 * These tests read the source manifest; the packed APK is additionally verified
 * with aapt2 at release time.
 */
class PackagingContractTest {

    private val manifest: String by lazy {
        File("src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun declaresInternetPermission() {
        assertTrue(
            "INTERNET permission required for Google Drive/Photos backup (v0.2.1 defect)",
            manifest.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun declaresRuntimeCriticalPermissions() {
        listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK"
        ).forEach { perm ->
            assertTrue("missing $perm", manifest.contains(perm))
        }
    }

    @Test
    fun widgetReceiverDeclaresProviderMetadata() {
        val receiverBlock = Regex(
            "<receiver[^>]*RemindWidgetReceiver[^>]*>.*?</receiver>",
            RegexOption.DOT_MATCHES_ALL
        ).find(manifest)?.value ?: error("RemindWidgetReceiver not declared in manifest")
        assertTrue(
            "widget receiver must declare APPWIDGET_UPDATE",
            receiverBlock.contains("android.appwidget.action.APPWIDGET_UPDATE")
        )
        assertTrue(
            "widget receiver must link provider xml via meta-data (v0.2 defect)",
            receiverBlock.contains("android.appwidget.provider") &&
                receiverBlock.contains("@xml/remind_widget_info")
        )
    }

    @Test
    fun widgetProviderXmlExists() {
        assertTrue(
            "res/xml/remind_widget_info.xml must exist",
            File("src/main/res/xml/remind_widget_info.xml").exists()
        )
    }

    @Test
    fun coreReceiversDeclared() {
        listOf(
            "ReminderReceiver", "BootReceiver", "SnoozeReceiver", "RemindWidgetReceiver"
        ).forEach { cls ->
            assertTrue("$cls not declared in manifest", manifest.contains(cls))
        }
    }
}
