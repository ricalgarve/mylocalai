package com.pocketpal.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LlamaServerService : Service() {

    private var server: HttpApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val CHANNEL_ID = "pocketpal_server_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_MODEL_PATH = "EXTRA_MODEL_PATH"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_THREADS = "EXTRA_THREADS"
        const val EXTRA_CTX = "EXTRA_CTX"
        const val EXTRA_DISABLE_THINK = "EXTRA_DISABLE_THINK"
        const val EXTRA_DISABLE_STREAM = "EXTRA_DISABLE_STREAM"

        var isRunning: Boolean = false
            private set
        var currentPort: Int = 8080
            private set

        var onLogListener: ((String) -> Unit)? = null
        var onStatusChangeListener: ((Boolean) -> Unit)? = null

        fun log(msg: String) {
            onLogListener?.invoke(msg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketPalServer::WakeLock")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
        } catch (e: Exception) {
            Log.w("LlamaServerService", "Could not acquire WakeLock: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PocketPalServer::WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("LlamaServerService", "Could not acquire WifiLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        if (action == ACTION_START) {
            val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: ""
            val port = intent.getIntExtra(EXTRA_PORT, 8080)
            val threads = intent.getIntExtra(EXTRA_THREADS, 4)
            val contextSize = intent.getIntExtra(EXTRA_CTX, 2048)
            val disableThink = intent.getBooleanExtra(EXTRA_DISABLE_THINK, true)
            val disableStream = intent.getBooleanExtra(EXTRA_DISABLE_STREAM, false)

            LlamaEngine.isThinkDisabled = disableThink
            LlamaEngine.isStreamDisabled = disableStream

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, buildNotification(port), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification(port))
                }
            } catch (e: Exception) {
                Log.w("LlamaServerService", "startForeground with type failed, falling back: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(port))
                } catch (e2: Exception) {
                    Log.e("LlamaServerService", "startForeground fallback failed: ${e2.message}")
                }
            }

            startServer(modelPath, port, threads, contextSize, disableThink, disableStream)
        } else if (action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startServer(
        modelPath: String,
        port: Int,
        threads: Int,
        contextSize: Int,
        disableThink: Boolean,
        disableStream: Boolean
    ) {
        currentPort = port
        isRunning = true
        onStatusChangeListener?.invoke(true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                log("Inicializando backend nativo GGML...")
                LlamaEngine.initBackend()
                log("Backend ativo: ${LlamaEngine.loadedBackendName}")
                log("Opções: Think Desabilitado = $disableThink | Stream Desabilitado = $disableStream")

                log("Carregando modelo GGUF ($threads threads, ctx: $contextSize)...")
                val loaded = LlamaEngine.loadModel(modelPath, threads, contextSize, gpuLayers = 0)
                if (!loaded) {
                    log("ERRO: Falha ao carregar o modelo. Verifique se o arquivo .gguf é válido e compatível.")
                    stopSelf()
                    return@launch
                }
                log("Modelo carregado com sucesso! (${LlamaEngine.currentModelName})")

                server = HttpApiServer(port) { logMessage ->
                    log(logMessage)
                }
                server?.start()
                log("Servidor HTTP ativo e escutando na porta $port")
            } catch (e: Throwable) {
                log("ERRO ao iniciar servidor: ${e.message}")
                Log.e("LlamaServerService", "Error during startServer", e)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
            LlamaEngine.free()
            log("Servidor e modelo finalizados.")
        } catch (e: Exception) {
            Log.e("LlamaServerService", "Error stopping server", e)
        } finally {
            isRunning = false
            onStatusChangeListener?.invoke(false)
        }
    }

    override fun onDestroy() {
        stopServer()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyLocal.AI Server Ativo")
            .setContentText("Servindo API OpenAI local na porta $port")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MyLocal.AI Background Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação persistente enquanto o servidor de IA estiver rodando."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
