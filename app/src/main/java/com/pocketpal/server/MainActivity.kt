package com.pocketpal.server

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.pocketpal.server.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedModelPath: String = ""

    // Chat Tab Data
    private val chatMessages = mutableListOf<ChatMessageModel>()
    private lateinit var chatAdapter: ChatAdapter
    private var isGenerating = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedModelUri(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Permissão de notificação recomendada para segundo plano.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Detect backend acceleration in background
        Thread {
            LlamaEngine.initBackend()
            runOnUiThread {
                binding.tvBackendInfo.text = LlamaEngine.loadedBackendName
            }
        }.start()

        // Set optimal default threads (4 for 8-core CPUs to target big performance cores)
        val defaultThreads = Runtime.getRuntime().availableProcessors().let {
            if (it >= 8) 4 else minOf(it, 4)
        }
        binding.etThreads.setText(defaultThreads.toString())

        // Setup Switch toggles
        binding.swDisableThink.isChecked = LlamaEngine.isThinkDisabled
        binding.swDisableStream.isChecked = LlamaEngine.isStreamDisabled

        binding.swDisableThink.setOnCheckedChangeListener { _, isChecked ->
            LlamaEngine.isThinkDisabled = isChecked
        }
        binding.swDisableStream.setOnCheckedChangeListener { _, isChecked ->
            LlamaEngine.isStreamDisabled = isChecked
        }

        // Load saved API token from preferences
        val prefs = getSharedPreferences("MyLocalAiPrefs", Context.MODE_PRIVATE)
        binding.etApiToken.setText(prefs.getString("api_token", ""))

        // Ensure finger scrolling inside log view is not intercepted by parent NestedScrollView
        val logTouchListener = View.OnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        binding.scrollLogs.setOnTouchListener(logTouchListener)
        binding.tvLogs.setOnTouchListener(logTouchListener)

        checkPermissions()
        setupListeners()
        setupChat()
        checkForExistingModel()
        updateUIState(LlamaServerService.isRunning)

        LlamaServerService.onLogListener = { logLine ->
            runOnUiThread {
                val current = binding.tvLogs.text.toString()
                binding.tvLogs.text = "$current\n$logLine"
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        LlamaServerService.onStatusChangeListener = { running ->
            runOnUiThread {
                updateUIState(running)
                updateChatUIState(running)
            }
        }
    }

    private fun setupListeners() {
        // Tab switching
        binding.btnTabServer.setOnClickListener { switchTab(isServer = true) }
        binding.btnTabChat.setOnClickListener { switchTab(isServer = false) }
        binding.btnGoToServerTab.setOnClickListener { switchTab(isServer = true) }

        // Server tab actions
        binding.btnSelectModel.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnGenerateToken.setOnClickListener {
            val generatedToken = "sk-local-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            binding.etApiToken.setText(generatedToken)
            Toast.makeText(this, "Novo token gerado!", Toast.LENGTH_SHORT).show()
        }

        // Copy cURL sample to clipboard
        val copyCurlAction = View.OnClickListener {
            val curlText = binding.tvCurlSample.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("cURL Sample", curlText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "cURL copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopyCurl.setOnClickListener(copyCurlAction)
        binding.tvCurlSample.setOnClickListener(copyCurlAction)

        binding.btnToggleServer.setOnClickListener {
            if (LlamaServerService.isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvLogs.text = "[Logs Limpos]"
        }

        // Chat tab actions
        binding.btnSendMessage.setOnClickListener {
            if (isGenerating) {
                stopGeneration()
            } else {
                sendMessage()
            }
        }

        binding.btnClearChat.setOnClickListener {
            chatAdapter.clearMessages()
            val greeting = if (LlamaEngine.isModelLoaded) {
                "Conversa limpa. Modelo '${LlamaEngine.currentModelName}' pronto para nova interação!"
            } else {
                "Conversa limpa. Inicie o servidor para interagir com o modelo."
            }
            chatAdapter.addMessage(ChatMessageModel("assistant", greeting))
        }
    }

    private fun switchTab(isServer: Boolean) {
        if (isServer) {
            binding.layoutServer.visibility = View.VISIBLE
            binding.layoutChat.visibility = View.GONE

            binding.btnTabServer.setBackgroundResource(R.drawable.bg_tab_active)
            binding.btnTabServer.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            binding.btnTabChat.setBackgroundResource(R.drawable.bg_tab_inactive)
            binding.btnTabChat.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.layoutServer.visibility = View.GONE
            binding.layoutChat.visibility = View.VISIBLE

            binding.btnTabServer.setBackgroundResource(R.drawable.bg_tab_inactive)
            binding.btnTabServer.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            binding.btnTabChat.setBackgroundResource(R.drawable.bg_tab_active)
            binding.btnTabChat.setTextColor(ContextCompat.getColor(this, R.color.text_primary))

            updateChatUIState(LlamaServerService.isRunning)
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter(chatMessages)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = chatAdapter

        // Initial welcome message
        chatAdapter.addMessage(
            ChatMessageModel(
                "assistant",
                "Olá! Sou a IA rodando localmente no seu aparelho. Como posso ajudar você hoje?"
            )
        )
    }

    private fun updateChatUIState(isRunning: Boolean) {
        if (isRunning && LlamaEngine.isModelLoaded) {
            binding.tvChatModelInfo.text = "Modelo: ${LlamaEngine.currentModelName}"
            binding.bannerModelNotLoaded.visibility = View.GONE
            binding.etChatMessage.isEnabled = true
            binding.btnSendMessage.isEnabled = true
        } else {
            binding.tvChatModelInfo.text = "Nenhum modelo ativo"
            binding.bannerModelNotLoaded.visibility = View.VISIBLE
            binding.etChatMessage.isEnabled = false
            binding.btnSendMessage.isEnabled = false
        }
    }

    private fun sendMessage() {
        val userText = binding.etChatMessage.text.toString().trim()
        if (userText.isEmpty()) return

        if (!LlamaEngine.isModelLoaded) {
            Toast.makeText(this, "Por favor, inicie o servidor primeiro para carregar o modelo.", Toast.LENGTH_SHORT).show()
            return
        }

        val disableStream = binding.swDisableStream.isChecked || LlamaEngine.isStreamDisabled
        val disableThink = binding.swDisableThink.isChecked || LlamaEngine.isThinkDisabled

        binding.etChatMessage.setText("")
        chatAdapter.addMessage(ChatMessageModel("user", userText))
        val initialPlaceholder = if (disableStream) "⏳ Gerando resposta completa..." else ""
        chatAdapter.addMessage(ChatMessageModel("assistant", initialPlaceholder))
        binding.rvChat.scrollToPosition(chatMessages.size - 1)

        isGenerating = true
        binding.btnSendMessage.text = "Parar"
        binding.btnSendMessage.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red))

        // Build prompt from full conversation history (excluding the empty last assistant message)
        val conversationHistory = mutableListOf<ChatMessage>()
        for (i in 0 until chatMessages.size - 1) {
            val m = chatMessages[i]
            conversationHistory.add(ChatMessage(role = m.role, content = m.content))
        }
        val prompt = LlamaEngine.formatChatMessages(conversationHistory, disableThink = disableThink)

        val uiHandler = Handler(Looper.getMainLooper())
        val tokenBuffer = StringBuilder()
        val fullAccumulator = StringBuilder()
        var updateScheduled = false
        val bufferLock = Any()

        fun flushBuffer() {
            val chunk: String
            synchronized(bufferLock) {
                chunk = tokenBuffer.toString()
                tokenBuffer.setLength(0)
                updateScheduled = false
            }
            if (chunk.isNotEmpty()) {
                chatAdapter.appendToLastMessage(chunk)
                binding.rvChat.scrollToPosition(chatMessages.size - 1)
            }
        }

        Thread {
            try {
                LlamaEngine.generate(
                    prompt = prompt,
                    temperature = 0.7f,
                    topP = 0.9f,
                    maxTokens = 1024,
                    callback = object : TokenCallback {
                        override fun onToken(token: String) {
                            if (disableStream) {
                                fullAccumulator.append(token)
                            } else {
                                synchronized(bufferLock) {
                                    tokenBuffer.append(token)
                                    if (!updateScheduled) {
                                        updateScheduled = true
                                        uiHandler.postDelayed({ flushBuffer() }, 40) // ~25 FPS batching eliminates UI thread overhead
                                    }
                                }
                            }
                        }

                        override fun onComplete() {
                            uiHandler.post {
                                if (disableStream) {
                                    chatAdapter.updateLastMessage(fullAccumulator.toString().trim())
                                    binding.rvChat.scrollToPosition(chatMessages.size - 1)
                                } else {
                                    flushBuffer()
                                }
                                finishGeneration()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                uiHandler.post {
                    chatAdapter.appendToLastMessage("\n[Erro de inferência: ${e.message}]")
                    finishGeneration()
                }
            }
        }.start()
    }

    private fun stopGeneration() {
        LlamaEngine.stop()
        finishGeneration()
    }

    private fun finishGeneration() {
        isGenerating = false
        binding.btnSendMessage.text = "Enviar"
        binding.btnSendMessage.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_accent))
    }

    private fun checkForExistingModel() {
        val destDir = getExternalFilesDir(null) ?: cacheDir
        val ggufFiles = destDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.length() > 0 }
        if (!ggufFiles.isNullOrEmpty()) {
            val latest = ggufFiles.maxByOrNull { it.lastModified() }
            if (latest != null) {
                selectedModelPath = latest.absolutePath
                binding.tvModelPath.text = "${latest.name} (${formatSize(latest.length())})\n[Pronto na memória local]"
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleSelectedModelUri(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        binding.tvModelPath.text = "Preparando $fileName..."
        binding.btnSelectModel.isEnabled = false
        binding.btnToggleServer.isEnabled = false

        Thread {
            try {
                var expectedSize = -1L
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst() && sizeIndex >= 0) {
                            expectedSize = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (_: Exception) {}

                val destDir = getExternalFilesDir(null) ?: cacheDir
                val destFile = File(destDir, fileName)

                // Check free disk space
                if (expectedSize > 0) {
                    val availableSpace = destDir.freeSpace
                    if (availableSpace < expectedSize) {
                        throw IOException("Espaço livre insuficiente no aparelho. Necessário: ${formatSize(expectedSize)}, Disponível: ${formatSize(availableSpace)}")
                    }
                }

                // If already copied and matches expected size, skip copying
                if (destFile.exists() && expectedSize > 0 && destFile.length() == expectedSize) {
                    runOnUiThread {
                        selectedModelPath = destFile.absolutePath
                        binding.tvModelPath.text = "${destFile.name} (${formatSize(destFile.length())})\n${destFile.absolutePath}"
                        binding.btnSelectModel.isEnabled = true
                        binding.btnToggleServer.isEnabled = true
                        Toast.makeText(this, "Modelo pronto: ${destFile.name}", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Otherwise, copy with progress tracking
                if (destFile.exists()) {
                    destFile.delete()
                }

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var bytesCopied = 0L
                        var bytesRead: Int
                        var lastProgressUpdate = System.currentTimeMillis()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 300) {
                                lastProgressUpdate = now
                                val pct = if (expectedSize > 0) (bytesCopied * 100 / expectedSize).toInt() else 0
                                runOnUiThread {
                                    if (expectedSize > 0) {
                                        binding.tvModelPath.text = "Copiando $fileName: $pct% (${formatSize(bytesCopied)} / ${formatSize(expectedSize)})"
                                    } else {
                                        binding.tvModelPath.text = "Copiando $fileName: ${formatSize(bytesCopied)}"
                                    }
                                }
                            }
                        }
                    }
                }

                selectedModelPath = destFile.absolutePath
                runOnUiThread {
                    binding.tvModelPath.text = "${destFile.name} (${formatSize(destFile.length())})\n${destFile.absolutePath}"
                    binding.btnSelectModel.isEnabled = true
                    binding.btnToggleServer.isEnabled = true
                    Toast.makeText(this, "Modelo pronto: ${destFile.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvModelPath.text = "Erro ao acessar modelo: ${e.message}"
                    binding.btnSelectModel.isEnabled = true
                    binding.btnToggleServer.isEnabled = true
                    Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startServer() {
        if (selectedModelPath.isEmpty() || !File(selectedModelPath).exists()) {
            Toast.makeText(this, "Por favor, selecione um arquivo .gguf válido primeiro.", Toast.LENGTH_LONG).show()
            return
        }

        val port = binding.etPort.text.toString().toIntOrNull() ?: 8080
        val threads = binding.etThreads.text.toString().toIntOrNull() ?: 4
        val contextSize = binding.etContextSize.text.toString().toIntOrNull() ?: 2048
        val disableThink = binding.swDisableThink.isChecked
        val disableStream = binding.swDisableStream.isChecked
        val apiToken = binding.etApiToken.text.toString().trim()

        // Persist token in SharedPreferences
        getSharedPreferences("MyLocalAiPrefs", Context.MODE_PRIVATE).edit()
            .putString("api_token", apiToken)
            .apply()

        LlamaEngine.isThinkDisabled = disableThink
        LlamaEngine.isStreamDisabled = disableStream

        val intent = Intent(this, LlamaServerService::class.java).apply {
            action = LlamaServerService.ACTION_START
            putExtra(LlamaServerService.EXTRA_MODEL_PATH, selectedModelPath)
            putExtra(LlamaServerService.EXTRA_PORT, port)
            putExtra(LlamaServerService.EXTRA_THREADS, threads)
            putExtra(LlamaServerService.EXTRA_CTX, contextSize)
            putExtra(LlamaServerService.EXTRA_DISABLE_THINK, disableThink)
            putExtra(LlamaServerService.EXTRA_DISABLE_STREAM, disableStream)
            putExtra(LlamaServerService.EXTRA_API_TOKEN, apiToken)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateUIState(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar serviço: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        val intent = Intent(this, LlamaServerService::class.java).apply {
            action = LlamaServerService.ACTION_STOP
        }
        startService(intent)
        updateUIState(false)
        updateChatUIState(false)
    }

    private fun updateUIState(isRunning: Boolean) {
        val port = binding.etPort.text.toString().toIntOrNull() ?: 8080
        val ip = getDeviceIpAddress()
        val apiToken = binding.etApiToken.text.toString().trim()

        if (isRunning) {
            binding.tvStatusBadge.text = getString(R.string.status_running)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.status_badge_running)
            binding.btnToggleServer.text = getString(R.string.btn_stop_server)
            binding.btnToggleServer.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red))

            binding.cardEndpoints.visibility = View.VISIBLE
            binding.tvEndpointLocal.text = "Localhost: http://localhost:$port/v1/chat/completions"
            binding.tvEndpointLan.text = "Rede Wi-Fi: http://$ip:$port/v1/chat/completions"

            val authHeader = if (apiToken.isNotEmpty()) "  -H \"Authorization: Bearer $apiToken\" \\\n" else ""
            binding.tvCurlSample.text = """curl http://$ip:$port/v1/chat/completions \
  -H "Content-Type: application/json" \
${authHeader}  -d '{"messages":[{"role":"user","content":"Olá!"}],"stream":true}'"""

            binding.btnSelectModel.isEnabled = false
            binding.etPort.isEnabled = false
            binding.etThreads.isEnabled = false
            binding.etContextSize.isEnabled = false
            binding.etApiToken.isEnabled = false
            binding.btnGenerateToken.isEnabled = false
            binding.swDisableThink.isEnabled = false
            binding.swDisableStream.isEnabled = false
            binding.tvBackendInfo.text = LlamaEngine.loadedBackendName
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_stopped)
            binding.tvStatusBadge.setBackgroundResource(R.drawable.status_badge_stopped)
            binding.btnToggleServer.text = getString(R.string.btn_start_server)
            binding.btnToggleServer.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_accent))

            binding.cardEndpoints.visibility = View.GONE

            binding.btnSelectModel.isEnabled = true
            binding.etPort.isEnabled = true
            binding.etThreads.isEnabled = true
            binding.etContextSize.isEnabled = true
            binding.etApiToken.isEnabled = true
            binding.btnGenerateToken.isEnabled = true
            binding.swDisableThink.isEnabled = true
            binding.swDisableStream.isEnabled = true
            binding.tvBackendInfo.text = LlamaEngine.loadedBackendName
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "model.gguf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
