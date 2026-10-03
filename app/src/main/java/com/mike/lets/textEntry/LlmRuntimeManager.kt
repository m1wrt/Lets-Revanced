package com.mike.lets.textEntry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

data class ModelConfig(
    val modelPath: String,
    val cacheDir: String,
    val numThreads: Int = 2,
    val numBatchThreads: Int = 2
)

object LlmRuntimeManager {
    private const val TAG = "LlamaCppClient"
    @Volatile private var engine: Engine? = null
    private var storedContext: Context? = null
    private var storedModelConfig: ModelConfig? = null
    private val executionMutex = Mutex()

    @JvmStatic
    fun isTensorDevice(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase(Locale.ROOT)
        } else {
            ""
        }
        val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
        val board = Build.BOARD.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        return soc.contains("tensor") || hardware.contains("tensor") || board.contains("tensor") ||
               hardware.contains("zumapro") || model.contains("pixel")
    }

    @JvmStatic
    fun hasOpenCL(): Boolean {
        val paths = listOf(
            "/system/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/odm/lib/libOpenCL.so",
            "/odm/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib/egl/libGLES_mali.so",
            "/vendor/lib/egl/libGLES_mali.so"
        )
        for (path in paths) {
            if (File(path).exists()) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun hasVulkan(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0) ||
               pm.hasSystemFeature("android.hardware.vulkan.level") ||
               pm.hasSystemFeature("android.hardware.vulkan.compute") ||
               pm.hasSystemFeature("android.hardware.vulkan.version")
    }

    @JvmStatic
    fun hasNNAPI(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature("android.hardware.neuralnetworks")
    }

    @JvmStatic
    fun canUseGpu(context: Context): Boolean {
        return false
    }

    private fun logMotorState(modelPath: String, backendName: String, numThreads: Int, details: String) {
        val banner = """
            
            ============================================================
             [LlamaCppClient] MOTOR LLM EN EJECUCIÓN (ESTABLE Y SEGURO)
             -> Backend Activo  : $backendName
             -> Detalle Backend : $details
             -> Ruta del Modelo : $modelPath
             -> Hilos / Batch   : $numThreads / $numThreads
             -> Max Tokens      : 128 (Optimizado)
             -> Dispositivo     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})
            ============================================================
        """.trimIndent()
        Log.i(TAG, banner)
    }

    @JvmStatic
    suspend fun initModelWithGpu(context: Context, modelConfig: ModelConfig) {
        synchronized(this) {
            storedContext = context.applicationContext
            storedModelConfig = modelConfig
            cleanupCurrentModelInternal()

            Log.i(TAG, "[LlamaCppClient] Inicializando motor seguro en CPU Alto Rendimiento...")
            val backend = Backend.CPU(modelConfig.numThreads, modelConfig.numBatchThreads)
            val config = EngineConfig(
                modelConfig.modelPath,
                backend,
                null,
                null,
                128,
                null,
                modelConfig.cacheDir
            )
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            logMotorState(modelConfig.modelPath, backend.name, modelConfig.numThreads, "CPU Alto Rendimiento (${modelConfig.numThreads} hilos)")
        }
    }

    @JvmStatic
    suspend fun initModelWithCpu(context: Context, modelConfig: ModelConfig) {
        synchronized(this) {
            storedContext = context.applicationContext
            storedModelConfig = modelConfig
            cleanupCurrentModelInternal()

            Log.i(TAG, "[LlamaCppClient] Inicializando motor explícito en CPU Alto Rendimiento...")
            val backend = Backend.CPU(modelConfig.numThreads, modelConfig.numBatchThreads)
            val config = EngineConfig(
                modelConfig.modelPath,
                backend,
                null,
                null,
                128,
                null,
                modelConfig.cacheDir
            )
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            logMotorState(modelConfig.modelPath, backend.name, modelConfig.numThreads, "CPU Explícito (${modelConfig.numThreads} hilos)")
        }
    }

    @JvmStatic
    suspend fun safeGenerate(prompt: String): String = executionMutex.withLock {
        val currentEngine = synchronized(this) {
            engine ?: throw IllegalStateException("Engine not initialized")
        }

        val samplerConfig = SamplerConfig(40, 0.9, 0.4, 0)
        val conversationConfig = ConversationConfig(null, ArrayList(), ArrayList(), samplerConfig)

        var conv: Conversation? = null
        val startTime = System.currentTimeMillis()
        try {
            conv = currentEngine.createConversation(conversationConfig)
            val message = conv.sendMessage(
                text = prompt,
                maxOutputToken = 25
            )
            val elapsedTimeMs = System.currentTimeMillis() - startTime
            val result = cleanOutput(extractText(message))
            logLiteRtPerformance(prompt, result, elapsedTimeMs)
            return result
        } catch (e: Throwable) {
            if (isOpenClError(e)) {
                Log.w(TAG, "[LlamaCppClient] Error detectado durante generación. Re-inicializando con CPU...", e)
                synchronized(this) {
                    cleanupCurrentModelInternal()
                    val ctx = storedContext
                    val cfg = storedModelConfig
                    if (ctx != null && cfg != null) {
                        val backend = Backend.CPU(cfg.numThreads, cfg.numBatchThreads)
                        val engineConfig = EngineConfig(
                            cfg.modelPath,
                            backend,
                            null,
                            null,
                            64,
                            null,
                            cfg.cacheDir
                        )
                        val eng = Engine(engineConfig)
                        eng.initialize()
                        engine = eng
                        logMotorState(cfg.modelPath, backend.name, cfg.numThreads, "CPU Fallback por Error en Generación")
                    } else {
                        throw e
                    }
                }

                val retryEngine = synchronized(this) {
                    engine ?: throw IllegalStateException("CPU fallback engine initialization failed")
                }
                var retryConv: Conversation? = null
                val retryStartTime = System.currentTimeMillis()
                try {
                    retryConv = retryEngine.createConversation(conversationConfig)
                    val retryMessage = retryConv.sendMessage(
                        text = prompt,
                        maxOutputToken = 25
                    )
                    val retryElapsedTimeMs = System.currentTimeMillis() - retryStartTime
                    val retryResult = cleanOutput(extractText(retryMessage))
                    logLiteRtPerformance(prompt, retryResult, retryElapsedTimeMs)
                    return retryResult
                } finally {
                    try { retryConv?.close() } catch (ignored: Exception) {}
                }
            } else {
                throw e
            }
        } finally {
            try { conv?.close() } catch (ignored: Exception) {}
        }
    }

    @JvmStatic
    fun safeGenerateStream(
        prompt: String,
        onPartial: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ): Job {
        return CoroutineScope(Dispatchers.Default).launch {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (ignored: Exception) {}

            executionMutex.withLock {
                val currentEngine = synchronized(this@LlmRuntimeManager) {
                    engine ?: run {
                        onError("Engine not initialized")
                        return@launch
                    }
                }

                val samplerConfig = SamplerConfig(40, 0.9, 0.4, 0)
                val conversationConfig = ConversationConfig(null, ArrayList(), ArrayList(), samplerConfig)

                var conv: Conversation? = null
                val startTime = System.currentTimeMillis()
                try {
                    conv = currentEngine.createConversation(conversationConfig)
                    val accumulatedText = StringBuilder()

                    conv.sendMessageStream(prompt).collect { chunk ->
                        accumulatedText.append(chunk)
                        onPartial(chunk)
                    }

                    val elapsedTimeMs = System.currentTimeMillis() - startTime
                    val fullResult = cleanOutput(accumulatedText.toString())
                    logLiteRtPerformance(prompt, fullResult, elapsedTimeMs)
                    onComplete(fullResult)

                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        Log.d(TAG, "Stream cancelled cleanly for prompt: $prompt")
                    } else {
                        Log.e(TAG, "Error in stream generation", e)
                        onError(e.message ?: "Stream error")
                    }
                } finally {
                    try { conv?.close() } catch (ignored: Exception) {}
                }
            }
        }
    }

    private fun logLiteRtPerformance(
        prompt: String,
        fullOutput: String,
        elapsedTimeMs: Long
    ) {
        val runtime = Runtime.getRuntime()
        val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)

        val context = storedContext
        val totalRamGb = if (context != null) LiteRTLMHardwareManager.getTotalRamInGb(context) else 0.0f
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifEmpty { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

        val tokenCount = fullOutput.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.coerceAtLeast(1)
        val tokensPerSecond = if (elapsedTimeMs > 0) {
            (tokenCount.toDouble() / (elapsedTimeMs / 1000.0))
        } else {
            0.0
        }

        val numThreads = storedModelConfig?.numThreads ?: 2

        val logBanner = String.format(
            Locale.US,
            "\n============================================================\n" +
            " LITERTM PERFORMANCE AND RESOURCE DIAGNOSTICS\n" +
            "------------------------------------------------------------\n" +
            " Execution Metrics:\n" +
            "   - Output Generation Time : %d ms (%.2f s)\n" +
            "   - Output Tokens Generated: %d tokens\n" +
            "   - Inference Speed        : %.2f tokens/sec\n" +
            " Resource Allocation:\n" +
            "   - Active Backend         : CPU High-Performance\n" +
            "   - CPU Threads Allocated  : %d threads\n" +
            "   - Java Heap Memory Used  : %d MB / %d MB\n" +
            "   - Total System RAM       : %.2f GB\n" +
            " Hardware Identification:\n" +
            "   - Device Model           : %s %s\n" +
            "   - Hardware / SoC         : %s (%s)\n" +
            "============================================================",
            elapsedTimeMs, elapsedTimeMs / 1000.0,
            tokenCount,
            tokensPerSecond,
            numThreads,
            usedMemoryMb, maxMemoryMb,
            totalRamGb,
            Build.MANUFACTURER, Build.MODEL,
            Build.HARDWARE, socModel
        )

        Log.i("litert", logBanner)
    }

    private fun isOpenClError(t: Throwable): Boolean {
        var curr: Throwable? = t
        while (curr != null) {
            val msg = curr.message ?: ""
            val clsName = curr.javaClass.name
            if (clsName.contains("LiteRtLmJniException", ignoreCase = true) ||
                msg.contains("OpenCL", ignoreCase = true) ||
                msg.contains("libOpenCL", ignoreCase = true) ||
                msg.contains("Can not find OpenCL", ignoreCase = true) ||
                msg.contains("nativeSendMessage", ignoreCase = true)) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    private fun cleanOutput(raw: String): String {
        var out = raw
        val fin = out.indexOf("<end_of_turn>")
        if (fin >= 0) out = out.substring(0, fin)
        out = out.replace(Regex("(?i)^(next:?\\s*|next\\s+words:?\\s*|oraci[oó]n:?\\s*|respuesta:?\\s*|output:?\\s*|result:?\\s*)"), "")
        return out.trim()
    }

    private fun extractText(msg: Message?): String {
        if (msg == null || msg.contents == null) return ""
        val sb = StringBuilder()
        for (c in msg.contents.contents) {
            if (c is Content.Text) {
                sb.append(c.text)
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun cleanupCurrentModel() {
        synchronized(this) {
            cleanupCurrentModelInternal()
        }
    }

    private fun cleanupCurrentModelInternal() {
        try {
            engine?.close()
        } catch (ignored: Exception) {}
        engine = null
    }

    @JvmStatic
    fun isReady(): Boolean = engine != null
}
