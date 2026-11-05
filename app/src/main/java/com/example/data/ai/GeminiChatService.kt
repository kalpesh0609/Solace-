package com.example.data.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiChatService"

enum class GeminiChatModel(val modelId: String, val displayName: String, val description: String) {
    FLASH("gemini-3.8-flash", "Gemini 3.8 Flash", "General wellness & empathic listening"),
    ARGON("gemini-4-argon", "Gemini 4 Argon", "Complex emotional reasoning & deep unpacking"),
    FLASH_LITE("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", "Fast somatic check-in & quick triage")
}

enum class ChatbotRole(val id: String, val title: String, val systemInstruction: String) {
    SANCTUARY_COMPANION(
        id = "companion",
        title = "Sanctuary Holding Presence",
        systemInstruction = """
            You are solace, a gentle, compassionate emotional sanctuary companion.
            Your role is to listen attentively, validate the user's emotions unconditionally, and hold unhurried space.
            Never be clinical, dismissive, or prescriptive. Avoid lecturing. Use warm, calming language.
            If the user is distressed, validate their feelings first before offering any gentle suggestions.
        """.trimIndent()
    ),
    SOMATIC_GUIDE(
        id = "somatic",
        title = "Somatic & Nervous System Guide",
        systemInstruction = """
            You are solace Somatic Guide, an expert in polyvagal theory, somatic grounding, and gentle breathwork.
            Help the user reconnect with their body, unclench muscle tension, and regulate their nervous system through concrete, micro-actions (e.g. dropping shoulders, feeling their feet on the floor, slow 4-count exhales).
        """.trimIndent()
    ),
    THOUGHT_CLARIFIER(
        id = "cbt",
        title = "Thought Clarifier (Gentle CBT)",
        systemInstruction = """
            You are solace Thought Clarifier. Help the user gently examine cognitive distortions (black-and-white thinking, catastrophizing, mind-reading) without feeling judged or wrong.
            Frame challenges as quiet questions: 'Is there another gentle possibility here?'
        """.trimIndent()
    ),
    WISDOM_MIRROR(
        id = "wisdom",
        title = "Deep Wisdom Mirror",
        systemInstruction = """
            You are solace Wisdom Mirror. Provide contemplative, philosophical, and mindfulness-grounded reflections that honor the natural rhythm and impermanence of all human feelings.
        """.trimIndent()
    )
}

class GeminiChatService {

    // Lazy model caches by model identifier and role
    private val modelMap = mutableMapOf<String, GenerativeModel>()

    private fun getModel(modelId: String, role: ChatbotRole): GenerativeModel {
        val cacheKey = "$modelId-${role.id}"
        return modelMap.getOrPut(cacheKey) {
            Firebase.ai.generativeModel(
                modelName = modelId,
                systemInstruction = Content(parts = listOf(TextPart(role.systemInstruction))),
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topK = 40
                }
            )
        }
    }

    /**
     * Multi-turn chat streaming with full conversation history
     */
    fun streamChatResponse(
        model: GeminiChatModel,
        role: ChatbotRole,
        history: List<Pair<String, String>>, // list of (role: "user" | "model", text)
        latestUserMessage: String,
        contextAnchor: String? = null
    ): Flow<String> = flow {
        val generativeModel = getModel(model.modelId, role)

        val formattedHistory = history.map { (senderRole, text) ->
            val aiRole = if (senderRole.equals("user", ignoreCase = true)) "user" else "model"
            Content(role = aiRole, parts = listOf(TextPart(text)))
        }

        val promptWithContext = if (!contextAnchor.isNullOrBlank()) {
            "[Current Sanctuary Context: $contextAnchor]\n$latestUserMessage"
        } else {
            latestUserMessage
        }

        try {
            val chat = generativeModel.startChat(formattedHistory)
            withTimeout(60_000L) {
                chat.sendMessageStream(promptWithContext).collect { response: GenerateContentResponse ->
                    val chunkText = response.text
                    if (!chunkText.isNullOrEmpty()) {
                        emit(chunkText)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Chat response timed out after 60s", e)
            emit("\n\n[The sanctuary connection took longer than expected. Take a gentle breath and feel free to try again.]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chat streaming failed with model ${model.modelId}", e)
            // Fallback to general model if argon or lite experienced quota/availability issues
            if (model != GeminiChatModel.FLASH) {
                try {
                    val fallbackModel = getModel(GeminiChatModel.FLASH.modelId, role)
                    val chat = fallbackModel.startChat(formattedHistory)
                    withTimeout(45_000L) {
                        chat.sendMessageStream(promptWithContext).collect { response: GenerateContentResponse ->
                            response.text?.let { emit(it) }
                        }
                    }
                    return@flow
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Fallback model also failed", fallbackEx)
                }
            }
            emit("I am here with you, though our connection fluttered. You are safe in this quiet space.")
        }
    }

    /**
     * Transcribe recorded audio using model gemini-3.5-transcribe
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String = "audio/mp4"
    ): String = withContext(Dispatchers.IO) {
        val transcribeModel = Firebase.ai.generativeModel(
            modelName = "gemini-3.5-transcribe",
            systemInstruction = Content(
                parts = listOf(
                    TextPart("You are a professional audio transcriber. Transcribe the spoken audio verbatim. Return only the exact transcribed text, with proper punctuation and capitalisation. Do not add commentary.")
                )
            )
        )

        try {
            val audioContent = Content(
                parts = listOf(
                    InlineDataPart(audioBytes, mimeType),
                    TextPart("Transcribe this spoken audio.")
                )
            )
            val response: GenerateContentResponse = withTimeout(60_000L) {
                transcribeModel.generateContent(audioContent)
            }
            val text = response.text
            if (!text.isNullOrBlank()) {
                text.trim()
            } else {
                "No speech was detected in this recording."
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Audio transcription timed out", e)
            throw IllegalStateException("Audio transcription timed out after 60 seconds.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed with gemini-3.5-transcribe, attempting flash fallback", e)
            try {
                val fallbackModel = Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
                val fallbackContent = Content(
                    parts = listOf(
                        InlineDataPart(audioBytes, mimeType),
                        TextPart("Please transcribe this spoken audio recording accurately into text. Output only the transcript.")
                    )
                )
                val response: GenerateContentResponse = withTimeout(45_000L) {
                    fallbackModel.generateContent(fallbackContent)
                }
                response.text?.trim() ?: "Transcription produced empty output."
            } catch (fallbackErr: Exception) {
                Log.e(TAG, "Transcription fallback also failed", fallbackErr)
                throw fallbackErr
            }
        }
    }
}
