package com.example.data.ai

import com.example.BuildConfig
import com.example.data.model.RegulationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SolaceAiService {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Deterministic Crisis Guardrails
    private val crisisKeywords = listOf(
        "suicide", "kill myself", "end my life", "want to die", "commit suicide",
        "self harm", "cut myself", "slit my wrist", "hurt myself", "take all my pills",
        "better off dead", "end it all"
    )

    fun checkCrisisSignal(userMessage: String): Boolean {
        val lower = userMessage.lowercase()
        return crisisKeywords.any { lower.contains(it) }
    }

    fun getCrisisResponse(): String {
        return "I hear the immense weight and pain in what you're sharing, and I care deeply about your safety. You don't have to carry this alone.\n\n" +
                "Please reach out to gentle human support right now:\n" +
                "• 988 Suicide & Crisis Lifeline: Call or text 988 (Available 24/7, free & confidential)\n" +
                "• Crisis Text Line: Text HOME to 741741\n" +
                "• The Trevor Project (LGBTQ+ youth): 1-866-488-7386\n" +
                "• International: www.befrienders.org\n\n" +
                "Take one slow, deep breath. Someone is ready to hold space for you."
    }

    suspend fun generateResponse(
        userName: String,
        currentMood: String?,
        moodIntensity: Int?,
        recentJournalSnippet: String?,
        regulationMode: RegulationMode,
        conversationHistory: List<Pair<String, String>>, // role, text
        latestUserMessage: String
    ): String = withContext(Dispatchers.IO) {
        // 1. First: Always check crisis guardrail
        if (checkCrisisSignal(latestUserMessage)) {
            return@withContext getCrisisResponse()
        }

        val apiKey = getApiKey()
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                return@withContext callGeminiApi(
                    apiKey = apiKey,
                    userName = userName,
                    currentMood = currentMood,
                    moodIntensity = moodIntensity,
                    recentJournalSnippet = recentJournalSnippet,
                    regulationMode = regulationMode,
                    conversationHistory = conversationHistory,
                    latestUserMessage = latestUserMessage
                )
            } catch (e: Exception) {
                // Fallback gracefully to offline sanctuary response
                android.util.Log.e("SolaceAiService", "Gemini API call failed, using sanctuary fallback", e)
            }
        }

        // Offline Sanctuary Engine
        delay(600) // gentle typing cadence simulation
        return@withContext generateLocalSanctuaryResponse(
            userName = userName,
            currentMood = currentMood,
            regulationMode = regulationMode,
            latestUserMessage = latestUserMessage
        )
    }

    private fun getApiKey(): String {
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun callGeminiApi(
        apiKey: String,
        userName: String,
        currentMood: String?,
        moodIntensity: Int?,
        recentJournalSnippet: String?,
        regulationMode: RegulationMode,
        conversationHistory: List<Pair<String, String>>,
        latestUserMessage: String
    ): String {
        val systemPrompt = buildSystemPrompt(userName, currentMood, moodIntensity, recentJournalSnippet, regulationMode)

        val contentsArray = JSONArray()

        // Include recent conversation turns (up to last 6)
        val recentHistory = conversationHistory.takeLast(6)
        for ((role, text) in recentHistory) {
            val partObj = JSONObject().put("text", text)
            val contentObj = JSONObject()
                .put("role", if (role == "user") "user" else "model")
                .put("parts", JSONArray().put(partObj))
            contentsArray.put(contentObj)
        }

        // Add latest user message
        contentsArray.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", latestUserMessage)))
        )

        val rootRequest = JSONObject()
        rootRequest.put("contents", contentsArray)

        val systemInstruction = JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        rootRequest.put("systemInstruction", systemInstruction)

        val generationConfig = JSONObject()
            .put("temperature", 0.7)
            .put("maxOutputTokens", 450)
        rootRequest.put("generationConfig", generationConfig)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = rootRequest.toString().toRequestBody(mediaType)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("API error: code ${response.code} body: ${response.body?.string()}")
            }
            val bodyString = response.body?.string() ?: throw IllegalStateException("Empty response body")
            val jsonResponse = JSONObject(bodyString)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text")
                return text.trim()
            }
            throw IllegalStateException("No candidate found in Gemini response")
        }
    }

    private fun buildSystemPrompt(
        userName: String,
        currentMood: String?,
        moodIntensity: Int?,
        recentJournalSnippet: String?,
        regulationMode: RegulationMode
    ): String {
        val moodInfo = if (currentMood != null) "Current recorded mood: $currentMood (Intensity: ${moodIntensity ?: 5}/10)." else "No mood recorded yet today."
        val journalInfo = if (!recentJournalSnippet.isNullOrBlank()) "Recent journal theme context: \"$recentJournalSnippet\"." else ""

        val modeGuidance = when (regulationMode) {
            RegulationMode.LISTEN -> "MODE: LISTEN. Prioritize reflective listening, acknowledgement and unconditional validation. Do NOT try to solve problems, offer quick fixes, or give advice. Be a soothing sanctuary mirror."
            RegulationMode.UNDERSTAND -> "MODE: UNDERSTAND. Help the user gently explore root causes and contradictory emotions with open-ended, compassionate inquiry."
            RegulationMode.CALM -> "MODE: CALM. Provide soothing, down-regulating somatic cues. Speak in short, relaxing cadences. Remind them to unclench their jaw, soften their shoulders, and breathe."
            RegulationMode.REFLECT -> "MODE: REFLECT. Offer gentle cognitive reframing. Help them see wisdom and growth in difficult moments without invalidating their hurt."
            RegulationMode.REFOCUS -> "MODE: REFOCUS. Help them unstick themselves by identifying a single, gentle, low-pressure micro-step they can take right now."
            RegulationMode.GRATITUDE -> "MODE: GRATITUDE. Gently guide attention toward small, tangible mercies, warmth, and reliefs from today without forced toxic positivity."
        }

        return """
            You are solace AI, the conversational companion inside solace+, a private digital emotional sanctuary.
            You are talking to $userName.
            $moodInfo
            $journalInfo
            
            Core Directives:
            1. You are empathetic, gentle, unhurried, and emotionally attuned.
            2. Never pretend to be human, and never claim to be a licensed therapist or clinician.
            3. Never lecture, preach, or use cheesy motivational quotes.
            4. Keep responses concise and conversational (2 to 4 gentle paragraphs at most).
            5. $modeGuidance
        """.trimIndent()
    }

    private fun generateLocalSanctuaryResponse(
        userName: String,
        currentMood: String?,
        regulationMode: RegulationMode,
        latestUserMessage: String
    ): String {
        val lower = latestUserMessage.lowercase()

        return when (regulationMode) {
            RegulationMode.LISTEN -> {
                if (lower.contains("overwhelm") || lower.contains("heavy") || lower.contains("too much")) {
                    "That makes complete sense, $userName. Feeling the weight of everything at once is so exhausting. You don't need to justify or untangle any of it right now. I'm here holding quiet space beside you. Take a slow breath—there is no rush to fix anything here."
                } else if (lower.contains("tired") || lower.contains("exhaust")) {
                    "I hear how bone-deep that tiredness is, $userName. Sometimes showing up to simply breathe is more than enough. You're allowed to set down every expectation in this space."
                } else {
                    "Thank you for sharing that with me, $userName. It takes courage to bring raw feelings into words. I'm listening with care—take all the time you need. What else feels present in your heart right now?"
                }
            }
            RegulationMode.CALM -> {
                "Let's pause right here together, $userName. Before trying to answer anything, notice where your feet are resting on the floor.\n\n" +
                "Drop your shoulders an inch away from your ears, and soften your jaw. Let's take one gentle 4-count breath in through your nose... and slowly exhale out through your mouth. Can you feel that subtle settling?"
            }
            RegulationMode.UNDERSTAND -> {
                "It sounds like there are several layers to what you're experiencing, $userName. Often when we feel this way, an underlying boundary or unvoiced need is calling for our attention. When did you first notice this sensation creeping in today?"
            }
            RegulationMode.REFLECT -> {
                "That is a profound thing to notice, $userName. Looking at this with kindness rather than self-judgment: what is this feeling trying to protect in you? Even our uncomfortable emotions are often quiet messengers guarding our energy."
            }
            RegulationMode.REFOCUS -> {
                "When everything feels like a storm, our minds try to solve twenty problems at once. Let's shrink the world down to just the next ten minutes, $userName. What is one tiny, gentle step you could take right now that requires no perfection?"
            }
            RegulationMode.GRATITUDE -> {
                "That touches on something very pure, $userName. In the middle of an imperfect day, finding even a single thread of warmth—like steam rising from tea or a quiet window—can anchor our nervous system. Let that small relief settle into your chest."
            }
        }
    }
}

data class CrisisHelpline(
    val name: String,
    val contact: String,
    val description: String
)

val CrisisHelplines = listOf(
    CrisisHelpline(
        name = "988 Suicide & Crisis Lifeline",
        contact = "Call or Text 988",
        description = "Free, confidential 24/7 support for anyone in emotional distress or suicidal crisis."
    ),
    CrisisHelpline(
        name = "Crisis Text Line",
        contact = "Text HOME to 741741",
        description = "Connect with a volunteer Crisis Counselor 24/7 over free text message."
    ),
    CrisisHelpline(
        name = "The Trevor Project",
        contact = "Call 1-866-488-7386 or Text START to 678-678",
        description = "24/7 confidential suicide prevention and crisis intervention for LGBTQ+ young people."
    ),
    CrisisHelpline(
        name = "Veterans Crisis Line",
        contact = "Dial 988, then press 1",
        description = "Caring, qualified responders with the Department of Veterans Affairs available 24/7."
    ),
    CrisisHelpline(
        name = "SAMHSA National Helpline",
        contact = "1-800-662-4357",
        description = "Treatment referral and information service for mental health and substance use concerns."
    )
)
