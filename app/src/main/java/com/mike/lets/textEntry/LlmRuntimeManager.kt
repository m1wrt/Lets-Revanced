package com.mike.lets.textEntry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.*
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
        try {
            conv = currentEngine.createConversation(conversationConfig)
            val message = conv.sendMessage(
                text = prompt,
                maxOutputToken = 25
            )
            return cleanOutput(extractText(message))
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
                try {
                    retryConv = retryEngine.createConversation(conversationConfig)
                    val retryMessage = retryConv.sendMessage(
                        text = prompt,
                        maxOutputToken = 25
                    )
                    return cleanOutput(extractText(retryMessage))
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
