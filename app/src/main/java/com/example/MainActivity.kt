package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

  companion object {
    var activeInstance: MainActivity? = null
  }

  private lateinit var webView: WebView
  private val channelId = "x5_reminders_channel_v2"
  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

  private val requestPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          Toast.makeText(this, "Izin notifikasi telah diberikan!", Toast.LENGTH_SHORT).show()
          webView.evaluateJavascript("if (typeof checkNotificationStatus === 'function') checkNotificationStatus();", null)
        } else {
          Toast.makeText(this, "Izin notifikasi tidak diberikan.", Toast.LENGTH_SHORT).show()
        }
      }

  private val fileChooserLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
          val data = result.data
          val uris: Array<Uri>? = when {
            data?.clipData != null -> {
              val count = data.clipData!!.itemCount
              Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
          }
          fileChooserCallback?.onReceiveValue(uris)
        } else {
          fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
      }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    activeInstance = this

    // Dark status bar and navigation bar to match monochromatic OLED theme #09090b
    window.statusBarColor = Color.parseColor("#09090b")
    window.navigationBarColor = Color.parseColor("#09090b")

    createNotificationChannel()
    AttendanceReceiver.createNotificationChannel(this)
    AttendanceReceiver.scheduleAlarms(this)

    webView = WebView(this).apply {
      setBackgroundColor(Color.parseColor("#09090b"))

      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT
        displayZoomControls = false
        builtInZoomControls = false
        setSupportZoom(false)
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          val uri = request?.url ?: return false
          val urlStr = uri.toString()
          if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
              this@MainActivity.startActivity(intent)
              return true
            } catch (e: Exception) {
              return false
            }
          }
          return false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          view?.evaluateJavascript("if (typeof checkNotificationStatus === 'function') checkNotificationStatus();", null)
          updateAttendanceStatusInWeb()
          if (intent?.getStringExtra("target_tab") == "presensi") {
            view?.evaluateJavascript("if (typeof switchTab === 'function') switchTab('presensi');", null)
          }
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
          request?.grant(request.resources)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
          fileChooserCallback?.onReceiveValue(null)
          fileChooserCallback = filePathCallback

          val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
          }

          val chooser = Intent.createChooser(intent, "Pilih Dokumen / File Spreadsheet")
          try {
            fileChooserLauncher.launch(chooser)
          } catch (e: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            return false
          }
          return true
        }
      }

      addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
    }

    setContentView(webView)

    // Load local HTML single page app from assets
    webView.loadUrl("file:///android_asset/index.html")

    // Handle Back Press in WebView
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
          webView.goBack()
        } else {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
        }
      }
    })

    // Check notification permission on modern Android
    checkNotificationPermission()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.getStringExtra("target_tab") == "presensi") {
      webView.evaluateJavascript("if (typeof switchTab === 'function') switchTab('presensi');", null)
    }
    updateAttendanceStatusInWeb()
  }

  override fun onDestroy() {
    super.onDestroy()
    if (activeInstance == this) {
      activeInstance = null
    }
  }

  fun updateAttendanceStatusInWeb() {
    val today = AttendanceReceiver.getTodayString()
    val pagi = AttendanceReceiver.getAttendanceStatus(this, "pagi")
    val sore = AttendanceReceiver.getAttendanceStatus(this, "sore")
    webView.evaluateJavascript("if (typeof onAttendanceStatusUpdated === 'function') onAttendanceStatusUpdated('$pagi', '$sore', '$today');", null)
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name = "Pengingat Prioritas X-5 SMAGA"
      val descriptionText = "Pengingat tugas mendesak, spam berkala, dan persiapan belajar harian kelas X-5 SMA Negeri 3 Jombang"
      val importance = NotificationManager.IMPORTANCE_HIGH
      val channel = NotificationChannel(channelId, name, importance).apply {
        description = descriptionText
        enableLights(true)
        lightColor = Color.WHITE
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 350, 150, 350)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          setBypassDnd(true)
        }
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  fun checkNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  inner class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun requestNotificationPermission() {
      runOnUiThread {
        checkNotificationPermission()
      }
    }

    @JavascriptInterface
    fun showNotification(title: String, message: String) {
      runOnUiThread {
        val intent = Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = try {
          BitmapFactory.decodeResource(context.resources, R.drawable.class_x5_logo)
        } catch (e: Exception) {
          null
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#18181b"))

        if (largeIcon != null) {
          builder.setLargeIcon(largeIcon)
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
          val notificationId = System.currentTimeMillis().toInt()
          NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
      }
    }

    @JavascriptInterface
    fun openFile(base64Data: String, mimeType: String, fileName: String) {
      runOnUiThread {
        try {
          val cleanBase64 = if (base64Data.contains(",")) {
            base64Data.substringAfter(",")
          } else {
            base64Data
          }
          val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
          val docsDir = File(context.cacheDir, "shared_documents")
          if (!docsDir.exists()) docsDir.mkdirs()

          val sanitizedFileName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
          val file = File(docsDir, sanitizedFileName)
          file.writeBytes(bytes)

          val contentUri = FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              file
          )

          val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType.ifEmpty { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }

          val chooser = Intent.createChooser(intent, "Buka $sanitizedFileName dengan...").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(chooser)
        } catch (e: Exception) {
          Toast.makeText(context, "Gagal membuka file: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }

    @JavascriptInterface
    fun openDndSettings() {
      runOnUiThread {
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
              putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
              putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
          } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
              putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
          }
        } catch (e: Exception) {
          Toast.makeText(context, "Silakan buka Pengaturan > Notifikasi > Abaikan Jangan Ganggu", Toast.LENGTH_SHORT).show()
        }
      }
    }

    @JavascriptInterface
    fun openBrowser(url: String) {
      runOnUiThread {
        try {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(intent)
        } catch (e: Exception) {
          Toast.makeText(context, "Gagal membuka link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }
    }

    @JavascriptInterface
    fun copyToClipboard(label: String, text: String) {
      runOnUiThread {
        try {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val clip = ClipData.newPlainText(label, text)
          clipboard.setPrimaryClip(clip)
          Toast.makeText(context, "$label disalin ke clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
          Toast.makeText(context, "Gagal menyalin: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }
    }

    @JavascriptInterface
    fun showToast(message: String) {
      runOnUiThread {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    }

    @JavascriptInterface
    fun getAttendanceStatus(session: String): String {
      return AttendanceReceiver.getAttendanceStatus(context, session)
    }

    @JavascriptInterface
    fun setAttendanceStatus(session: String, status: String) {
      AttendanceReceiver.setAttendanceStatus(context, session, status)
      runOnUiThread {
        updateAttendanceStatusInWeb()
      }
      AttendanceReceiver.scheduleAlarms(context)
    }

    @JavascriptInterface
    fun resetAttendanceStatus(session: String) {
      AttendanceReceiver.resetAttendanceStatus(context, session)
      runOnUiThread {
        updateAttendanceStatusInWeb()
      }
      AttendanceReceiver.scheduleAlarms(context)
    }

    @JavascriptInterface
    fun testAttendanceNotification(session: String) {
      runOnUiThread {
        AttendanceReceiver().showNotification(context, session, isTest = true)
      }
    }

    @JavascriptInterface
    fun scheduleAttendanceAlarms() {
      AttendanceReceiver.scheduleAlarms(context)
    }
  }
}
