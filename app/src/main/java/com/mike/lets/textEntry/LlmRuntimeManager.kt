package com.mike.lets.textEntry

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class ModelConfig(
    val modelPath: String,
    val cacheDir: String,
    val numThreads: Int = 4,
    val numBatchThreads: Int = 4
)

object LlmRuntimeManager {
    private const val TAG = "LlmRuntimeManager"
    private var engine: Engine? = null
    private val mutex = Mutex()
    private var storedContext: Context? = null
    private var storedModelConfig: ModelConfig? = null

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
        return hasOpenCL() || hasVulkan(context)
    }

    @JvmStatic
    suspend fun initModelWithGpu(context: Context, modelConfig: ModelConfig) {
        mutex.withLock {
            storedContext = context.applicationContext
            storedModelConfig = modelConfig
            cleanupCurrentModelInternal()
            Log.d(TAG, "Initializing model with safe backend (CPU fallback to prevent VK_ERROR_DEVICE_LOST): ${modelConfig.modelPath}")
            // Use Backend.CPU for stability and to prevent GPU device lost crashes (VK_ERROR_DEVICE_LOST)
            val backend = Backend.CPU(modelConfig.numThreads, modelConfig.numBatchThreads)
            val config = EngineConfig(
                modelConfig.modelPath,
                backend,
                null,
                null,
                null,
                null,
                modelConfig.cacheDir
            )
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            Log.d(TAG, "Engine initialized successfully.")
        }
    }

    @JvmStatic
    suspend fun initModelWithCpu(context: Context, modelConfig: ModelConfig) {
        mutex.withLock {
            storedContext = context.applicationContext
            storedModelConfig = modelConfig
            cleanupCurrentModelInternal()
            Log.d(TAG, "Initializing model with CPU backend: ${modelConfig.modelPath}")
            val backend = Backend.CPU(modelConfig.numThreads, modelConfig.numBatchThreads)
            val config = EngineConfig(
                modelConfig.modelPath,
                backend,
                null,
                null,
                null,
                null,
                modelConfig.cacheDir
            )
            val eng = Engine(config)
            eng.initialize()
            engine = eng
            Log.d(TAG, "Engine initialized with CPU successfully.")
        }
    }

    @JvmStatic
    suspend fun safeGenerate(prompt: String): String {
        val currentEngine = mutex.withLock {
            engine ?: throw IllegalStateException("Engine not initialized")
        }

        val samplerConfig = SamplerConfig(64, 0.95, 0.5, 0)
        val conversationConfig = ConversationConfig(null, ArrayList(), ArrayList(), samplerConfig)

        var conv: Conversation? = null
        try {
            conv = currentEngine.createConversation(conversationConfig)
            val message = conv.sendMessage(prompt)
            return extractText(message)
        } catch (e: Throwable) {
            if (isOpenClError(e)) {
                Log.w(TAG, "OpenCL/LiteRtLmJniException error detected during generation. Re-initializing with CPU and retrying once...", e)
                mutex.withLock {
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
                            null,
                            null,
                            cfg.cacheDir
                        )
                        val eng = Engine(engineConfig)
                        eng.initialize()
                        engine = eng
                    } else {
                        throw e
                    }
                }

                val retryEngine = mutex.withLock {
                    engine ?: throw IllegalStateException("CPU fallback engine initialization failed")
                }
                var retryConv: Conversation? = null
                try {
                    retryConv = retryEngine.createConversation(conversationConfig)
                    val retryMessage = retryConv.sendMessage(prompt)
                    return extractText(retryMessage)
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
                msg.contains("Can not find OpenCL", ignoreCase = true)) {
                return true
            }
            curr = curr.cause
        }
        return false
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
