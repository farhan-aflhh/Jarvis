package com.farhan.jarvis

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var accessKey: String
        get() = p.getString("access_key", "") ?: ""
        set(v) = p.edit().putString("access_key", v.trim()).apply()

    var brainCode: String
        get() = p.getString("brain_code", "") ?: ""
        set(v) = p.edit().putString("brain_code", v.trim()).apply()

    var icsUrl: String
        get() = p.getString("ics_url", "") ?: ""
        set(v) = p.edit().putString("ics_url", v.trim()).apply()
}
