package org.stypox.dicio.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface LlmInferenceEngine {
    suspend fun generate(prompt: String): String
    val isReady: Boolean
    suspend fun close()
}

// a request after this long without one starts a new conversation: follow-up questions keep their
// context, but the context does not grow without bound (LiteRT-LM fails beyond maxNumTokens)
const val LLM_CONVERSATION_IDLE_MS = 5 * 60 * 1000L

// low: tool calls and short factual answers should be reproducible, not creative
const val LLM_TEMPERATURE = 0.2

const val LLM_SYSTEM_INSTRUCTION =
    "You are a helpful voice assistant. You do TWO kinds of things: " +
    "(a) answer factual and general-knowledge questions from your training, and " +
    "(b) execute actions by calling tools (timers, alarms, opening apps, URLs, dialing, " +
    "calendar events, web search). Never refuse a general-knowledge question just because " +
    "no tool matches — answer it from your own knowledge. " +
    "When the user asks what you can do, briefly list both capabilities in their language. " +
    "When the user asks you to perform an action that matches a tool, call the tool " +
    "IMMEDIATELY — do NOT produce any text before the tool call. " +
    "After the tool response arrives, reply with exactly one short confirmation sentence " +
    "in the user's language (e.g. \"Timer für 3 Minuten gestartet.\" on success, or a brief " +
    "explanation of the problem on failure). Never repeat yourself and never end your turn " +
    "silently after a tool call. " +
    "ALWAYS reply in the exact same language as the user's most recent message, even if the " +
    "tool schema or descriptions are in English. Keep textual answers to at most two short " +
    "sentences. " +
    "Use plain text only — no markdown, no bold, no bullet points, no headings, no code."

// relative times ("tomorrow at 3 pm", "in two hours") need today's date, which the model cannot know
fun llmSystemInstruction(): String = LLM_SYSTEM_INSTRUCTION + " The current local date and time is " +
    SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date()) + "."
