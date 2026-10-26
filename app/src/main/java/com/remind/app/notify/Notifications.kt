package com.remind.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_ALARMS = "alarms"
    const val CHANNEL_WATER = "water"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_ALARMS, "Alarm reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(alarmSound, attrs); enableVibration(true)
            },
            NotificationChannel(CHANNEL_WATER, "Water", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    fun post(context: Context, id: Int, title: String, text: String, alarmStyle: Boolean, reminderId: Long? = null) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE)
        val channel = if (alarmStyle) CHANNEL_ALARMS else CHANNEL_REMINDERS
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.remind.app.R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (alarmStyle) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            builder.setFullScreenIntent(pi, true) // lock-screen ring where the system allows it
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        }
        if (reminderId != null) {
            for ((delayMs, label, rc) in listOf(
                Triple(3_600_000L, "Snooze 1 hour", 1),
                Triple(86_400_000L, "Snooze 1 day", 2)
            )) {
                val si = Intent(context, SnoozeReceiver::class.java)
                    .putExtra(Scheduler.EXTRA_ID, reminderId)
                    .putExtra(Scheduler.EXTRA_DELAY, delayMs)
                val spi = PendingIntent.getBroadcast(
                    context, (reminderId * 10 + rc).toInt(), si,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0, label, spi)
            }
        }
        try { NotificationManagerCompat.from(context).notify(id, builder.build()) } catch (_: SecurityException) { }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun postWater(context: Context, done: Int, total: Int) {
        val pi = PendingIntent.getActivity(context, 9001,
            context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_WATER)
            .setSmallIcon(com.remind.app.R.drawable.ic_notification)
            .setContentTitle("Water").setContentText("Time to drink water · $done/$total today")
            .setAutoCancel(true).setContentIntent(pi)
        try { NotificationManagerCompat.from(context).notify(9001, builder.build()) } catch (_: SecurityException) { }
    }
}
