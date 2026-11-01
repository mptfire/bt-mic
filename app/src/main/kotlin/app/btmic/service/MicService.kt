package app.btmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import app.btmic.engine.AacSink
import app.btmic.engine.RecordEngine
import app.btmic.store.NoteStore
import app.btmic.transcribe.Transcriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SKILL.md "Background recording": FGS type microphone, screen-off owner.
 * On Android 14+ the mic FGS cannot record after reboot/update until the app
 * is opened once — we surface that instead of failing silently.
 */
class MicService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: RecordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground("Starting…")
        // Wrapped acquire (pitfall 13): a missing lock never ends a recording.
        runCatching {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btmic:rec")
                .also { it.acquire(4 * 60 * 60 * 1000L) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_STICKY
        if (!openedSinceBoot()) {
            startInForeground(getString(app.btmic.R.string.open_once))
            return START_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startInForeground(getString(app.btmic.R.string.no_permission))
            return START_STICKY
        }
        startInForeground(getString(app.btmic.R.string.waiting_bt))
        val (m4a, txt) = NoteStore.newNames()
        val transcriber = Transcriber(this)
        engine = RecordEngine(this, scope, AacSink(m4a)).also { e ->
            scope.launch {
                e.ui.collect { u ->
                    app.btmic.ui.EngineBridge.publish(u)
                    when (u) {
                        is RecordEngine.Ui.RecordingBt ->
                            startInForeground(getString(app.btmic.R.string.rec_bt))
                        is RecordEngine.Ui.RecordingPhone ->
                            startInForeground(getString(app.btmic.R.string.rec_phone))
                        else -> {}
                    }
                }
            }
            e.onSaved = {
                app.btmic.ui.EngineBridge.idle()
                scope.launch {
                    if (m4a.exists() && m4a.length() > 0) {
                        transcriber.transcribeFile(m4a, txt)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(app.btmic.R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, n)
    }

    private fun openedSinceBoot(): Boolean =
        getSharedPreferences("btmic", MODE_PRIVATE).getBoolean("opened", false)

    override fun onDestroy() {
        engine?.stop()
        engine = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "btmic"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "app.btmic.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MicService::class.java))
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, MicService::class.java).setAction(ACTION_STOP))
        }

        fun channel(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, context.getString(app.btmic.R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW))
        }
    }
}
