package com.autoclicker.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lưu cấu hình hiện tại và tối đa 5 bản lưu (slot) bằng SharedPreferences. */
object Storage {
    const val MAX_SLOTS = 5
    private const val PREF = "autoclicker"
    private const val KEY_SLOTS = "slots"
    private const val KEY_CURRENT = "current"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadCurrent(c: Context): Profile {
        val s = prefs(c).getString(KEY_CURRENT, null) ?: return Profile("Mặc định")
        return try {
            Profile.fromJson(JSONObject(s))
        } catch (_: Exception) {
            Profile("Mặc định")
        }
    }

    fun saveCurrent(c: Context, p: Profile) {
        prefs(c).edit().putString(KEY_CURRENT, p.toJson().toString()).apply()
    }

    fun loadSlots(c: Context): Array<Profile?> {
        val result = arrayOfNulls<Profile>(MAX_SLOTS)
        val s = prefs(c).getString(KEY_SLOTS, null) ?: return result
        try {
            val arr = JSONArray(s)
            for (i in 0 until minOf(arr.length(), MAX_SLOTS)) {
                if (!arr.isNull(i)) result[i] = Profile.fromJson(arr.getJSONObject(i))
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Ghi (hoặc xoá nếu profile = null) bản lưu ở vị trí index (0..4). */
    fun saveSlot(c: Context, index: Int, profile: Profile?) {
        if (index !in 0 until MAX_SLOTS) return
        val slots = loadSlots(c)
        slots[index] = profile
        val arr = JSONArray()
        for (i in 0 until MAX_SLOTS) {
            val p = slots[i]
            if (p == null) arr.put(JSONObject.NULL) else arr.put(p.toJson())
        }
        prefs(c).edit().putString(KEY_SLOTS, arr.toString()).apply()
    }
}
