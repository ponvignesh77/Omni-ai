package com.example.aiassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AiVoiceController(
    private val context: Context,
    private val apiKey: String
) {
    private var tts: TextToSpeech? = null
    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                
                // Trigger the multi-step agent loop
                startAgentLoop(originalGoal = spokenText)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    // THE AGENT LOOP: Runs iteratively until the task is DONE or max steps reached
    private fun startAgentLoop(originalGoal: String, maxSteps: Int = 5, currentStep: Int = 1) {
        if (currentStep > maxSteps) {
            tts?.speak("I've reached the maximum steps for this task.", TextToSpeech.QUEUE_FLUSH, null, "AGENT_LIMIT")
            return
        }

        val control = PhoneControlService.instance
        if (control == null) {
            tts?.speak("Accessibility service is not enabled.", TextToSpeech.QUEUE_FLUSH, null, "ERR")
            return
        }

        val uiHierarchy = control.getScreenUiSummary()

        control.captureScreenBitmap { bitmap ->
            scope.launch(Dispatchers.IO) {
                var base64Image: String? = null
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, stream)
                    base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }

                // Ask Gemini what to do next based on the current screen state
                val responseJson = callGeminiAgentApi(originalGoal, uiHierarchy, base64Image, currentStep)
                
                scope.launch(Dispatchers.Main) {
                    val action = responseJson.optString("action", "DONE")
                    val target = responseJson.optString("target", "")
                    val thought = responseJson.optString("thought", "")
                    val reply = responseJson.optString("reply", "")

                    if (reply.isNotEmpty() && currentStep == 1) {
                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "AGENT_REPLY")
                    }

                    when (action) {
                        "DONE" -> {
                            val finalMsg = if (reply.isNotEmpty()) reply else "Task completed successfully."
                            tts?.speak(finalMsg, TextToSpeech.QUEUE_FLUSH, null, "AGENT_DONE")
                        }
                        else -> {
                            // 1. Execute current step's action
                            executeSingleAction(action, target)

                            // 2. Wait 2 seconds for the app/screen to load completely
                            delay(2000)

                            // 3. Recurse to the next step in the loop
                            startAgentLoop(originalGoal, maxSteps, currentStep + 1)
                        }
                    }
                }
            }
        }
    }

    private fun callGeminiAgentApi(
        goal: String,
        screenElements: String,
        base64Screenshot: String?,
        step: Int
    ): JSONObject {
        return try {
            val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val systemInstructions = """
                You are an autonomous Android AI Agent executing a multi-step user goal.
                Overall User Goal: "$goal"
                Current Step: $step (Max 5)
                Visible Screen UI Elements: [$screenElements]

                Analyze the current screen screenshot and elements. Determine the NEXT single action required to move closer to the goal. 
                If the goal is fully accomplished, set action to "DONE".
                
                Respond ONLY with raw JSON in this exact format:
                {
                  "thought": "Short explanation of what you see and why you chose this next action",
                  "action": "OPEN_APP" | "CLICK" | "TYPE" | "SYSTEM" | "TAP_XY" | "DONE",
                  "target": "App name, exact button text to click, text to type, system command, X,Y coords, or empty if DONE",
                  "reply": "Optional brief spoken update for the user"
                }
            """.trimIndent()

            val partsArray = org.json.JSONArray().apply {
                put(JSONObject().put("text", systemInstructions))
                if (base64Screenshot != null) {
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Screenshot)
                    }))
                }
            }

            val payload = JSONObject().put(
                "contents", org.json.JSONArray().put(
                    JSONObject().put("parts", partsArray)
                )
            )

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val rawResponse = conn.inputStream.bufferedReader().readText()
            val textReply = JSONObject(rawResponse)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .removePrefix("```json")
                .removeSuffix("```")
                .trim()

            JSONObject(textReply)
        } catch (e: Exception) {
            JSONObject().apply {
                put("action", "DONE")
                put("reply", "Agent loop encountered an error: ${e.localizedMessage}")
            }
        }
    }

    private fun executeSingleAction(action: String, target: String) {
        val control = PhoneControlService.instance ?: return
        when (action) {
            "OPEN_APP" -> control.openAppByName(target)
            "CLICK" -> control.clickTextOnScreen(target)
            "TYPE" -> control.typeTextIntoFocusedField(target)
            "SYSTEM" -> control.performSystemAction(target)
            "TAP_XY" -> {
                val coords = target.split(",")
                if (coords.size == 2) {
                    control.tapCoordinates(coords[0].trim().toFloat(), coords[1].trim().toFloat())
                }
            }
        }
    }
}
