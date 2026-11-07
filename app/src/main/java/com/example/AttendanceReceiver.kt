package com.example

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AttendanceReceiver : BroadcastReceiver() {

  companion object {
    const val CHANNEL_ID = "x5_attendance_channel"
    const val PREFS_NAME = "x5_attendance_prefs"

    const val ACTION_ALARM_CHECK = "com.example.ACTION_ATTENDANCE_ALARM_CHECK"
    const val ACTION_ATTENDANCE_ACTION = "com.example.ACTION_ATTENDANCE_ACTION"
    const val ACTION_TEST_NOTIFICATION = "com.example.ACTION_ATTENDANCE_TEST_NOTIFICATION"

    const val EXTRA_SESSION = "extra_session" // "pagi" or "sore"
    const val EXTRA_STATUS = "extra_status"   // "sudah" or "tidak_masuk"

    const val NOTIF_ID_PAGI = 7001
    const val NOTIF_ID_SORE = 7002

    fun getTodayString(): String {
      return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    fun getPrefs(context: Context): SharedPreferences {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAttendanceStatus(context: Context, session: String): String {
      val today = getTodayString()
      return getPrefs(context).getString("${session}_$today", "belum") ?: "belum"
    }

    fun setAttendanceStatus(context: Context, session: String, status: String) {
      val today = getTodayString()
      getPrefs(context).edit().putString("${session}_$today", status).apply()
      if (status == "sudah" || status == "tidak_masuk") {
        cancelNotification(context, session)
      }
    }

    fun resetAttendanceStatus(context: Context, session: String) {
      val today = getTodayString()
      getPrefs(context).edit().remove("${session}_$today").apply()
    }

    fun cancelNotification(context: Context, session: String) {
      val notifId = if (session == "pagi") NOTIF_ID_PAGI else NOTIF_ID_SORE
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.cancel(notifId)
    }

    fun createNotificationChannel(context: Context) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "Pengingat Presensi Online X-5 SMAGA"
        val desc = "Pengingat absen lokasi e-Rapor dan absen wajah Sigmajo setiap 5 menit (06.30-07.00 & 15.00-15.30)"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
          description = desc
          enableLights(true)
          lightColor = Color.WHITE
          enableVibration(true)
          vibrationPattern = longArrayOf(0, 400, 200, 400)
          lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setBypassDnd(true)
          }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
      }
    }

    fun scheduleAlarms(context: Context) {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      val calendar = Calendar.getInstance()
      val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

      // Skip Sunday (1) and Saturday (7)
      if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) {
        // Schedule for next Monday 06:30
        scheduleForNextSchoolDay(context, alarmManager)
        return
      }

      val hour = calendar.get(Calendar.HOUR_OF_DAY)
      val minute = calendar.get(Calendar.MINUTE)
      val currentTotalMinutes = hour * 60 + minute

      val morningStart = 6 * 60 + 30   // 06:30 (390 min)
      val morningEnd = 7 * 60          // 07:00 (420 min)
      val afternoonStart = 15 * 60     // 15:00 (900 min)
      val afternoonEnd = 15 * 60 + 30  // 15:30 (930 min)

      var nextTriggerMillis: Long = -1

      if (currentTotalMinutes in morningStart..morningEnd) {
        val pagiStatus = getAttendanceStatus(context, "pagi")
        if (pagiStatus != "sudah" && pagiStatus != "tidak_masuk") {
          // Trigger notification immediately or in 5 minutes
          nextTriggerMillis = System.currentTimeMillis() + (5 * 60 * 1000L)
        } else {
          // Already answered, schedule for afternoon 15:00 today
          nextTriggerMillis = getMillisForTimeToday(15, 0)
        }
      } else if (currentTotalMinutes < morningStart) {
        // Schedule for 06:30 today
        nextTriggerMillis = getMillisForTimeToday(6, 30)
      } else if (currentTotalMinutes in (morningEnd + 1) until afternoonStart) {
        // Between 07:01 and 14:59, schedule for 15:00 today
        nextTriggerMillis = getMillisForTimeToday(15, 0)
      } else if (currentTotalMinutes in afternoonStart..afternoonEnd) {
        val soreStatus = getAttendanceStatus(context, "sore")
        if (soreStatus != "sudah" && soreStatus != "tidak_masuk") {
          nextTriggerMillis = System.currentTimeMillis() + (5 * 60 * 1000L)
        } else {
          // Schedule for tomorrow 06:30
          nextTriggerMillis = getMillisForTomorrow(6, 30)
        }
      } else {
        // After 15:30, schedule for tomorrow 06:30
        nextTriggerMillis = getMillisForTomorrow(6, 30)
      }

      if (nextTriggerMillis > System.currentTimeMillis()) {
        val intent = Intent(context, AttendanceReceiver::class.java).apply {
          action = ACTION_ALARM_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
          context,
          8801,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMillis, pendingIntent)
          } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMillis, pendingIntent)
          }
        } catch (e: Exception) {
          alarmManager.set(AlarmManager.RTC_WAKEUP, nextTriggerMillis, pendingIntent)
        }
      }
    }

    private fun scheduleForNextSchoolDay(context: Context, alarmManager: AlarmManager) {
      val calendar = Calendar.getInstance()
      while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
        calendar.add(Calendar.DAY_OF_YEAR, 1)
      }
      calendar.set(Calendar.HOUR_OF_DAY, 6)
      calendar.set(Calendar.MINUTE, 30)
      calendar.set(Calendar.SECOND, 0)
      calendar.set(Calendar.MILLISECOND, 0)

      val intent = Intent(context, AttendanceReceiver::class.java).apply {
        action = ACTION_ALARM_CHECK
      }
      val pendingIntent = PendingIntent.getBroadcast(
        context,
        8801,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
      } catch (e: Exception) {
        alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
      }
    }

    private fun getMillisForTimeToday(hour: Int, minute: Int): Long {
      val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      return calendar.timeInMillis
    }

    private fun getMillisForTomorrow(hour: Int, minute: Int): Long {
      val calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      return calendar.timeInMillis
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    createNotificationChannel(context)
    val action = intent.action ?: return

    when (action) {
      ACTION_ATTENDANCE_ACTION -> {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: "pagi"
        val status = intent.getStringExtra(EXTRA_STATUS) ?: "sudah"
        handleUserAction(context, session, status)
      }

      ACTION_ALARM_CHECK, Intent.ACTION_BOOT_COMPLETED -> {
        handleAlarmCheck(context)
      }

      ACTION_TEST_NOTIFICATION -> {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: "pagi"
        showNotification(context, session, isTest = true)
      }
    }
  }

  private fun handleUserAction(context: Context, session: String, status: String) {
    setAttendanceStatus(context, session, status)
    cancelNotification(context, session)

    val sessionLabel = if (session == "pagi") "Pagi (06.30 - 07.00)" else "Pulang (15.00 - 15.30)"
    val statusLabel = if (status == "sudah") "✅ Sudah Absen" else "❌ Tidak Masuk"

    Toast.makeText(
      context,
      "Presensi $sessionLabel dicatat: $statusLabel.\nPengingat otomatis dihentikan.",
      Toast.LENGTH_LONG
    ).show()

    // Notify MainActivity Web UI to refresh state if visible
    MainActivity.activeInstance?.runOnUiThread {
      MainActivity.activeInstance?.updateAttendanceStatusInWeb()
    }

    // Schedule next window
    scheduleAlarms(context)
  }

  private fun handleAlarmCheck(context: Context) {
    val calendar = Calendar.getInstance()
    val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) {
      scheduleAlarms(context)
      return
    }

    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val currentTotalMinutes = hour * 60 + minute

    val morningStart = 6 * 60 + 30   // 06:30
    val morningEnd = 7 * 60          // 07:00
    val afternoonStart = 15 * 60     // 15:00
    val afternoonEnd = 15 * 60 + 30  // 15:30

    if (currentTotalMinutes in morningStart..morningEnd) {
      val status = getAttendanceStatus(context, "pagi")
      if (status != "sudah" && status != "tidak_masuk") {
        showNotification(context, "pagi", isTest = false)
      }
    } else if (currentTotalMinutes in afternoonStart..afternoonEnd) {
      val status = getAttendanceStatus(context, "sore")
      if (status != "sudah" && status != "tidak_masuk") {
        showNotification(context, "sore", isTest = false)
      }
    }

    scheduleAlarms(context)
  }

  fun showNotification(context: Context, session: String, isTest: Boolean = false) {
    createNotificationChannel(context)

    val notifId = if (session == "pagi") NOTIF_ID_PAGI else NOTIF_ID_SORE
    val sessionTitle = if (session == "pagi") "PRESENSI PAGI (06.30 - 07.00)" else "PRESENSI PULANG (15.00 - 15.30)"
    val prefix = if (isTest) "[TEST PENGINGAT 5 MENIT] " else "📍 "

    val title = "$prefix$sessionTitle"
    val content = "Segera absen di e-Rapor atau Sigmajo SMAN 3 Jombang! Pengingat muncul tiap 5 menit sampai kamu klik tombol di bawah."

    // PendingIntent to open app
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
      putExtra("target_tab", "presensi")
    }
    val openAppPending = PendingIntent.getActivity(
      context,
      notifId,
      openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Action 1: "Sudah Absen"
    val sudahIntent = Intent(context, AttendanceReceiver::class.java).apply {
      action = ACTION_ATTENDANCE_ACTION
      putExtra(EXTRA_SESSION, session)
      putExtra(EXTRA_STATUS, "sudah")
    }
    val sudahPending = PendingIntent.getBroadcast(
      context,
      notifId + 10,
      sudahIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Action 2: "Tidak Masuk"
    val tidakMasukIntent = Intent(context, AttendanceReceiver::class.java).apply {
      action = ACTION_ATTENDANCE_ACTION
      putExtra(EXTRA_SESSION, session)
      putExtra(EXTRA_STATUS, "tidak_masuk")
    }
    val tidakMasukPending = PendingIntent.getBroadcast(
      context,
      notifId + 20,
      tidakMasukIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val largeIcon = try {
      BitmapFactory.decodeResource(context.resources, R.drawable.class_x5_logo)
    } catch (e: Exception) {
      null
    }

    val bigText = """
      • Absen Lokasi: eraporaio.sman3jombang.sch.id
      • Absen Wajah: sigmajo.sman3jombang.sch.id
      
      Klik "Sudah Absen" atau "Tidak Masuk" untuk menghentikan notifikasi 5 menitan ini.
    """.trimIndent()

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_alert)
      .setContentTitle(title)
      .setContentText(content)
      .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setContentIntent(openAppPending)
      .setAutoCancel(true)
      .setOngoing(false)
      .setColor(Color.parseColor("#18181b"))
      .addAction(android.R.drawable.checkbox_on_background, "✅ Sudah Absen", sudahPending)
      .addAction(android.R.drawable.ic_delete, "❌ Tidak Masuk", tidakMasukPending)

    if (largeIcon != null) {
      builder.setLargeIcon(largeIcon)
    }

    NotificationManagerCompat.from(context).notify(notifId, builder.build())
  }
}
