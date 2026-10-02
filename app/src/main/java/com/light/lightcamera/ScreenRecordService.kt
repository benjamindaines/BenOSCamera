package com.light.lightcamera

import android.Manifest
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordService : Service(), ScreenOverlayView.OverlayListener {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null

    private var isRecording = false
    private var isPaused = false
    private var startTimeMillis = 0L
    private var pausedDurationMillis = 0L
    private var pauseStartTimeMillis = 0L

    private var outputUri: Uri? = null
    private var outputFilePath: String? = null
    private var screenOverlayView: ScreenOverlayView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                val elapsedMillis = System.currentTimeMillis() - startTimeMillis - pausedDurationMillis
                val formattedTime = formatElapsedTime(elapsedMillis)
                updateNotification(formattedTime)
                screenOverlayView?.updateTimerText(formattedTime)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startRecordingProcess(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecordingProcess()
            }
            ACTION_PAUSE -> {
                pauseRecordingProcess()
            }
            ACTION_RESUME -> {
                resumeRecordingProcess()
            }
        }

        return START_STICKY
    }

    private fun startRecordingProcess(resultCode: Int, resultData: Intent) {
        if (isRecording) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val audioSetting = prefs.getString("screen_record_audio", "mic") ?: "mic"
        val hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasAudio = hasAudioPermission && audioSetting != "muted"

        val initialNotification = buildNotification("00:00")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (hasAudio && audioSetting == "mic") {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, initialNotification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        val projection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "MediaProjection token is null")
            stopSelf()
            return
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecordingProcess()
            }
        }, handler)

        try {
            setupMediaRecorder(projection)
            mediaRecorder?.start()
            isRecording = true
            isPaused = false
            startTimeMillis = System.currentTimeMillis()
            pausedDurationMillis = 0L

            handler.post(timerRunnable)

            // Show floating overlay if enabled and permitted
            val showOverlay = prefs.getBoolean("screen_record_overlay", true)
            if (showOverlay && Settings.canDrawOverlays(this)) {
                screenOverlayView = ScreenOverlayView(this, this)
                screenOverlayView?.showOverlay()
            }

            Toast.makeText(this, R.string.screen_record_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            val details = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Toast.makeText(this, "Failed to start screen recording: $details", Toast.LENGTH_LONG).show()
            cleanup()
            stopSelf()
        }
    }

    private fun setupMediaRecorder(projection: MediaProjection) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val resolutionSetting = prefs.getString("screen_record_resolution", "native") ?: "native"
        val fpsSetting = prefs.getString("screen_record_fps", "60")?.toIntOrNull() ?: 60
        val audioSetting = prefs.getString("screen_record_audio", "mic") ?: "mic"

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        val (targetWidth, targetHeight) = calculateTargetDimensions(screenWidth, screenHeight, resolutionSetting)

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var hasAudio = false

        if (hasAudioPermission) {
            when (audioSetting) {
                "mic" -> {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    hasAudio = true
                }
                "internal" -> {
                    recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    hasAudio = true
                }
            }
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        if (hasAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
        }

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(targetWidth, targetHeight)
        recorder.setVideoFrameRate(fpsSetting)

        val bitrate = when (resolutionSetting) {
            "1080p", "native" -> 12000000
            "720p" -> 8000000
            "480p" -> 4000000
            else -> 10000000
        }
        recorder.setVideoEncodingBitRate(bitrate)

        // Setup output file in MediaStore
        val name = "ScreenRecord_" + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BenOSCamera")
            }
        }

        val storageLocation = prefs.getString("storage_location", "internal") ?: "internal"
        val collection = if (storageLocation == "sd_card" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volumes = MediaStore.getExternalVolumeNames(this)
            val sdVolume = volumes.find { it != MediaStore.VOLUME_EXTERNAL_PRIMARY && it != MediaStore.VOLUME_EXTERNAL }
            if (sdVolume != null) MediaStore.Video.Media.getContentUri(sdVolume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = contentResolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry for video recording")
        outputUri = uri
        outputFilePath = name

        val openPfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Failed to open FileDescriptor for output URI: $uri")
        pfd = openPfd
        recorder.setOutputFile(openPfd.fileDescriptor)

        recorder.prepare()
        mediaRecorder = recorder

        virtualDisplay = projection.createVirtualDisplay(
            "BenOSCamera_ScreenRecord",
            targetWidth,
            targetHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            handler
        )
    }

    private fun calculateTargetDimensions(srcWidth: Int, srcHeight: Int, setting: String): Pair<Int, Int> {
        val maxDim = when (setting) {
            "1080p" -> 1920
            "720p" -> 1280
            "480p" -> 854
            else -> Math.max(srcWidth, srcHeight)
        }

        val currentMax = Math.max(srcWidth, srcHeight)
        if (currentMax <= maxDim || setting == "native") {
            return Pair(srcWidth and 1.inv(), srcHeight and 1.inv())
        }

        val scale = maxDim.toFloat() / currentMax
        var targetW = (srcWidth * scale).toInt() and 1.inv()
        var targetH = (srcHeight * scale).toInt() and 1.inv()

        if (targetW <= 0) targetW = 2
        if (targetH <= 0) targetH = 2

        return Pair(targetW, targetH)
    }

    private fun pauseRecordingProcess() {
        if (!isRecording || isPaused) return
        try {
            mediaRecorder?.pause()
            isPaused = true
            pauseStartTimeMillis = System.currentTimeMillis()
            updateNotification("PAUSED")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing recording", e)
        }
    }

    private fun resumeRecordingProcess() {
        if (!isRecording || !isPaused) return
        try {
            mediaRecorder?.resume()
            isPaused = false
            pausedDurationMillis += System.currentTimeMillis() - pauseStartTimeMillis
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
        }
    }

    private fun stopRecordingProcess() {
        if (!isRecording) {
            cleanup()
            stopSelf()
            return
        }

        handler.removeCallbacks(timerRunnable)
        isRecording = false
        isPaused = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }

        val uri = outputUri
        val name = outputFilePath ?: "Screen Recording"

        cleanup()

        if (uri != null) {
            val msg = getString(R.string.screen_record_stopped, name)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(openIntent)
            } catch (e: Exception) {
                Log.e(TAG, "No app to open video", e)
            }
        }

        stopSelf()
    }

    private fun cleanup() {
        screenOverlayView?.removeOverlay()
        screenOverlayView = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null

        try {
            pfd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ParcelFileDescriptor", e)
        }
        pfd = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null
    }

    override fun onPauseClicked() {
        pauseRecordingProcess()
    }

    override fun onResumeClicked() {
        resumeRecordingProcess()
    }

    override fun onStopClicked() {
        stopRecordingProcess()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active screen recording status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(timerText: String): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 1, pauseResumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseTitle = if (isPaused) getString(R.string.resume_screen_record) else getString(R.string.pause_screen_record)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_record_notification_title))
            .setContentText("${getString(R.string.screen_record_notification_text)} ($timerText)")
            .setSmallIcon(R.drawable.ic_videocam)
            .setOngoing(true)
            .addAction(R.drawable.ic_pause, pauseTitle, pauseResumePendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_screen_record), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(timerText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(timerText))
    }

    private fun formatElapsedTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        cleanup()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 2001
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        const val ACTION_START = "com.light.lightcamera.action.START_SCREEN_RECORD"
        const val ACTION_STOP = "com.light.lightcamera.action.STOP_SCREEN_RECORD"
        const val ACTION_PAUSE = "com.light.lightcamera.action.PAUSE_SCREEN_RECORD"
        const val ACTION_RESUME = "com.light.lightcamera.action.RESUME_SCREEN_RECORD"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startService(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
