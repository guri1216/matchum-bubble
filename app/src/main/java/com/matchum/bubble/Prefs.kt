package com.matchum.bubble

import android.content.Context

/** 앱 설정 저장소 (휴대폰 안에만 저장됨) */
object Prefs {
    const val DEFAULT_MODEL = "claude-haiku-5-5"

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun aiEnabled(c: Context) = sp(c).getBoolean("ai_enabled", false)
    fun setAiEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("ai_enabled", v).apply()

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = sp(c).edit().putString("api_key", v).apply()

    fun model(c: Context): String =
        (sp(c).getString("model", "") ?: "").ifBlank { DEFAULT_MODEL }
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()

    /** AI 버튼을 보여줄 수 있는 상태인지 */
    fun aiReady(c: Context) = aiEnabled(c) && apiKey(c).isNotBlank()
}
