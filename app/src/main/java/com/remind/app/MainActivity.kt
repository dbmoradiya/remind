package com.remind.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.remind.app.ui.AppViewModel
import com.remind.app.ui.RemindNavHost
import com.remind.app.ui.RemindTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { maybeAskExactAlarm() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            RemindTheme(settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    RemindNavHost(rememberNavController(), vm)
                }
            }
        }
        askNotificationPermission()
    }

    /** Android 13+ gate: without this the app shows zero notifications. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            maybeAskExactAlarm()
        }
    }

    /** Android 12+ gate: without the exact-alarm grant, reminders fall back to inexact
     *  daily checks. Once per install, offer the system settings screen. */
    private fun maybeAskExactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return
        val am = getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) return
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean("asked_exact_alarm", false)) return
        prefs.edit().putBoolean("asked_exact_alarm", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }
}
