/*
 * LiteRTLMHardwareManager.kt
 *
 * Módulo unificado de detección de hardware y configuración de ejecución
 * para modelos LiteRT-LM en Android.
 *
 * Este archivo agrupa:
 * 1. Detección de hardware (RAM, SoC, GPUs Mali antiguas, dispositivos Pixel).
 * 2. Selección automática y segura de aceleradores (GPU, NPU, TPU, CPU).
 * 3. Construcción de EngineConfig y ConversationConfig para LiteRT-LM.
 */

package com.mike.lets.textEntry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "LiteRTHardwareManager"
private const val BYTES_IN_GB = 1024f * 1024f * 1024f

/**
 * Enumeración de los tipos de aceleradores de hardware soportados para LiteRT-LM.
 */
enum class HardwareAccelerator(val label: String) {
    CPU(label = "CPU"),
    GPU(label = "GPU"),
    NPU(label = "NPU"),
    TPU(label = "TPU");

    companion object {
        /**
         * Obtiene un [HardwareAccelerator] a partir de su etiqueta o nombre (case-insensitive).
         */
        fun fromLabel(label: String?): HardwareAccelerator? {
            if (label == null) return null
            return entries.firstOrNull {
                it.label.equals(label, ignoreCase = true) || it.name.equals(label, ignoreCase = true)
            }
        }
    }
}

/**
 * Información diagnóstica del hardware detectado en el dispositivo.
 *
 * @property totalRamGb Memoria RAM total publicitada o disponible en Gigabytes.
 * @property socModel Modelo de SoC detectado (ej. "Tensor G4", "Snapdragon 8 Gen 3").
 * @property isPixelDevice `true` si el dispositivo pertenece a la línea Google Pixel.
 * @property isPixel10 `true` si el dispositivo es específicamente un Google Pixel 10.
 * @property isOldMaliGpu `true` si el dispositivo posee una GPU Mali/Exynos antigua no recomendada para GPU delegate.
 */
data class HardwareDiagnostics(
    val totalRamGb: Float,
    val socModel: String,
    val isPixelDevice: Boolean,
    val isPixel10: Boolean,
    val isOldMaliGpu: Boolean
)

/**
 * Gestor unificado para la detección de hardware y configuración del runtime LiteRT-LM.
 */
object LiteRTLMHardwareManager {

    private val PIXEL_9_CODENAMES = setOf("tokay", "caiman", "komodo", "comet", "tegu")

    // ============================================================================================
    // 1. DETECCIÓN DE HARDWARE Y MEMORIA
    // ============================================================================================

    /**
     * Obtiene el modelo de SoC (System on Chip) del dispositivo en minúsculas.
     *
     * @return El nombre del SoC si está disponible en Android 12+ (API 31), o una cadena vacía en versiones anteriores.
     */
    fun getSocModel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (Build.SOC_MODEL ?: "").lowercase(Locale.ROOT)
        } else {
            ""
        }
    }

    /**
     * Comprueba si el dispositivo pertenece a la línea Google Pixel.
     */
    fun isPixelDevice(): Boolean {
        return Build.MODEL != null && Build.MODEL.lowercase(Locale.ROOT).contains("pixel")
    }

    /**
     * Comprueba si el dispositivo es específicamente un Google Pixel 10.
     */
    fun isPixel10(): Boolean {
        return Build.MODEL != null && Build.MODEL.lowercase(Locale.ROOT).contains("pixel 10")
    }

    /**
     * Obtiene la cantidad total de memoria RAM del dispositivo en Gigabytes.
     * En Android 14+ (API 34) utiliza [ActivityManager.MemoryInfo.advertisedMem] para obtener
     * el valor comercial exacto de la RAM. En versiones anteriores usa `totalMem`.
     *
     * @param context El contexto de la aplicación.
     * @return Memoria RAM total en GB, o `0.0f` si no se puede determinar.
     */
    fun getTotalRamInGb(context: Context): Float {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0.0f
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            memoryInfo.advertisedMem / BYTES_IN_GB
        } else {
            memoryInfo.totalMem / BYTES_IN_GB
        }
    }

    /**
     * Verifica si la memoria RAM del dispositivo es menor que el mínimo requerido por un modelo.
     *
     * @param context El contexto de la aplicación.
     * @param minRequiredMemoryGb La cantidad mínima de RAM requerida por el modelo en GB.
     * @return `true` si la memoria del dispositivo es menor al mínimo requerido; `false` en caso contrario.
     */
    fun isMemoryLow(context: Context, minRequiredMemoryGb: Int?): Boolean {
        if (minRequiredMemoryGb == null) return false
        val deviceRamGb = getTotalRamInGb(context)
        Log.d(TAG, "RAM detectada: $deviceRamGb GB. Mínimo requerido: $minRequiredMemoryGb GB.")
        return deviceRamGb < minRequiredMemoryGb
    }

    /**
     * Detecta si el dispositivo posee una GPU Mali/Exynos de generación anterior que podría
     * experimentar problemas de estabilidad o rendimiento bajo ejecuciones pesadas de LiteRT-LM.
     *
     * @return `true` si la GPU es una Mali antigua; `false` si es una GPU moderna o de otra arquitectura (ej. Adreno).
     */
    fun isOldMaliGpu(
        hardware: String = Build.HARDWARE,
        board: String = Build.BOARD,
        socModel: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL ?: "" else "",
        device: String = Build.DEVICE,
        model: String = Build.MODEL ?: ""
    ): Boolean {
        val lowerModel = model.lowercase(Locale.ROOT)
        val lowerDevice = device.lowercase(Locale.ROOT)
        val lowerBoard = board.lowercase(Locale.ROOT)
        val lowerSocModel = socModel.lowercase(Locale.ROOT)
        val lowerHardware = hardware.lowercase(Locale.ROOT)

        // Pixel 9 y posteriores (Tensor G4) son compatibles con GPU.
        if (lowerModel.contains("pixel 9") ||
            lowerDevice in PIXEL_9_CODENAMES ||
            lowerBoard in PIXEL_9_CODENAMES ||
            lowerSocModel.contains("tensor g4") ||
            lowerHardware.contains("zumapro")
        ) {
            return false
        }

        val combined = "$lowerHardware $lowerBoard $lowerSocModel"
        return !combined.contains("malibu") &&
                (combined.contains("exynos") || combined.contains("mali"))
    }

    /**
     * Devuelve una estructura completa con el diagnóstico de hardware del dispositivo.
     *
     * @param context El contexto de la aplicación.
     */
    fun getDiagnostics(context: Context): HardwareDiagnostics {
        return HardwareDiagnostics(
            totalRamGb = getTotalRamInGb(context),
            socModel = getSocModel(),
            isPixelDevice = isPixelDevice(),
            isPixel10 = isPixel10(),
            isOldMaliGpu = isOldMaliGpu()
        )
    }

    // ============================================================================================
    // 2. SELECCIÓN DE ACELERADORES Y FILTRADO
    // ============================================================================================

    /**
     * Filtra y resuelve la lista de aceleradores compatibles para el dispositivo actual.
     * Aplica restricciones de seguridad como la desactivación de GPU en Pixel 10 o advertencia de Mali antiguas.
     *
     * @param supportedAccelerators Lista de aceleración soportada por el modelo (ej. `[GPU, NPU, CPU]`).
     * @return Lista filtrada de aceleradores seguros para el dispositivo actual.
     */
    fun resolveCompatibleAccelerators(
        supportedAccelerators: List<HardwareAccelerator>
    ): List<HardwareAccelerator> {
        val result = supportedAccelerators.toMutableList()

        // Restricción de seguridad: Pixel 10 utiliza NPU/TPU o CPU preferentemente.
        if (isPixel10()) {
            Log.w(TAG, "Dispositivo Pixel 10 detectado. Desactivando opción GPU.")
            result.remove(HardwareAccelerator.GPU)
        }

        if (result.isEmpty()) {
            result.add(HardwareAccelerator.CPU)
        }

        return result
    }

    /**
     * Mapea un [HardwareAccelerator] a un [Backend] nativo de LiteRT-LM.
     *
     * @param accelerator Acelerador seleccionado.
     * @param context Contexto de la aplicación (requerido para obtener el `nativeLibraryDir` en NPU/TPU).
     * @return Objeto [Backend] para configurar el motor de LiteRT.
     */
    fun toLiteRtBackend(accelerator: HardwareAccelerator, context: Context): Backend {
        return when (accelerator) {
            HardwareAccelerator.CPU -> Backend.CPU()
            HardwareAccelerator.GPU -> Backend.GPU()
            HardwareAccelerator.NPU, HardwareAccelerator.TPU -> {
                Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            }
        }
    }

    // ============================================================================================
    // 3. CONSTRUCCIÓN DE CONFIGURACIONES PARA LITERT-LM
    // ============================================================================================

    /**
     * Construye un [EngineConfig] optimizado para inicializar la clase `Engine` de LiteRT-LM.
     *
     * @param context Contexto de la aplicación.
     * @param modelPath Ruta absoluta al archivo del modelo `.bin` o `.task`.
     * @param preferredAccelerator Acelerador primario seleccionado para el modelo.
     * @param visionAccelerator Acelerador para la modalidad de visión en modelos multimodales (por defecto `GPU`).
     * @param supportAudio Indica si la modalidad de audio está activa (utiliza `CPU`).
     * @param maxTokens Número máximo de tokens configurados.
     * @return Instancia lista de [EngineConfig].
     */
    fun createEngineConfig(
        context: Context,
        modelPath: String,
        preferredAccelerator: HardwareAccelerator = HardwareAccelerator.GPU,
        visionAccelerator: HardwareAccelerator? = HardwareAccelerator.GPU,
        supportAudio: Boolean = false,
        maxTokens: Int = 2048
    ): EngineConfig {
        val backend = toLiteRtBackend(preferredAccelerator, context)
        val visionBackend = visionAccelerator?.let { toLiteRtBackend(it, context) }
        val audioBackend = if (supportAudio) Backend.CPU() else null

        val cacheDir = if (modelPath.startsWith("/data/local/tmp")) {
            context.getExternalFilesDir(null)?.absolutePath
        } else {
            null
        }

        Log.d(TAG, "Creando EngineConfig -> Backend: $preferredAccelerator, Vision: $visionAccelerator, Audio: $supportAudio")

        return EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = maxTokens,
            cacheDir = cacheDir
        )
    }

    /**
     * Construye un [ConversationConfig] adecuando la configuración de muestreadores (`SamplerConfig`).
     *
     * IMPORTANTE: Cuando se ejecuta en `NPU` o `TPU`, [SamplerConfig] se establece como `null`
     * debido a que el hardware de la NPU gestiona el muestreo directamente en firmware o kernel.
     *
     * @param accelerator Acelerador primario utilizado en la conversación.
     * @param topK Parámetro TopK de muestreo (se ignora en NPU/TPU).
     * @param topP Parámetro TopP de muestreo (se ignora en NPU/TPU).
     * @param temperature Parámetro de temperatura (se ignora en NPU/TPU).
     * @param systemInstruction Instrucciones de sistema para la conversación (opcional).
     * @param tools Proveedores de herramientas / funciones callable (opcional).
     * @return Instancia de [ConversationConfig].
     */
    fun createConversationConfig(
        accelerator: HardwareAccelerator,
        topK: Int = 40,
        topP: Float = 0.95f,
        temperature: Float = 0.8f,
        systemInstruction: Contents? = null,
        tools: List<ToolProvider> = emptyList()
    ): ConversationConfig {
        val samplerConfig = if (accelerator == HardwareAccelerator.NPU || accelerator == HardwareAccelerator.TPU) {
            Log.d(TAG, "Acelerador NPU/TPU activo: omitiendo SamplerConfig explícito.")
            null
        } else {
            SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble()
            )
        }

        return ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = systemInstruction,
            tools = tools
        )
    }
}

private fun extractTextFromMessage(msg: Message?): String {
    if (msg == null || msg.contents == null) return ""
    val sb = StringBuilder()
    for (c in msg.contents.contents) {
        if (c is Content.Text) {
            sb.append(c.text)
        }
    }
    return sb.toString()
}

/**
 * Función de extensión opcional para convertir las respuestas asíncronas de [Conversation]
 * en un [Flow] de Kotlin Coroutines con soporte nativo para **Streaming** token por token.
 *
 * @param prompt Texto enviado por el usuario.
 * @return [Flow] que emite cada nuevo fragmento/token generado en tiempo real.
 */
fun Conversation.sendMessageStream(prompt: String): Flow<String> = callbackFlow {
    sendMessageAsync(
        Contents.of(Content.Text(prompt)),
        object : MessageCallback {
            override fun onMessage(message: Message) {
                val text = extractTextFromMessage(message)
                trySend(if (text.isNotEmpty()) text else message.toString())
            }

            override fun onDone() {
                close()
            }

            override fun onError(throwable: Throwable) {
                close(throwable)
            }
        }
    )
    awaitClose {
        try {
            close()
        } catch (e: Exception) {
            // Ignorar si la conversación ya había finalizado
        }
    }
}
