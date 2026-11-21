package com.autoclicker.app

import org.json.JSONArray
import org.json.JSONObject

/** Một điểm tự động bấm trên màn hình (toạ độ tâm, tính bằng pixel). */
data class ClickPoint(
    val id: Int,
    var x: Int,
    var y: Int,
    var intervalMs: Long = 1000L,   // khoảng cách giữa 2 lần bấm
    var holdMs: Long = 50L,         // thời gian giữ mỗi lần bấm
    var repeat: Int = 0             // số lần bấm, 0 = vô hạn
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("x", x).put("y", y)
        .put("interval", intervalMs).put("hold", holdMs).put("repeat", repeat)

    companion object {
        fun fromJson(o: JSONObject) = ClickPoint(
            id = o.getInt("id"),
            x = o.getInt("x"),
            y = o.getInt("y"),
            intervalMs = o.optLong("interval", 1000L),
            holdMs = o.optLong("hold", 50L),
            repeat = o.optInt("repeat", 0)
        )
    }
}

/** Toàn bộ thông số cài đặt = 1 bản lưu. */
data class Profile(
    var name: String,
    val points: MutableList<ClickPoint> = mutableListOf(),
    var runAll: Boolean = true,      // true: chạy tất cả điểm, false: chỉ chạy điểm đang chọn
    var selectedId: Int = -1,
    var startDelayMs: Long = 0L      // trễ trước khi bắt đầu chạy
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        points.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("name", name).put("runAll", runAll)
            .put("selected", selectedId).put("delay", startDelayMs)
            .put("points", arr)
    }

    fun copy(): Profile = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val list = mutableListOf<ClickPoint>()
            val arr = o.optJSONArray("points") ?: JSONArray()
            for (i in 0 until arr.length()) list.add(ClickPoint.fromJson(arr.getJSONObject(i)))
            return Profile(
                name = o.optString("name", "Bản lưu"),
                points = list,
                runAll = o.optBoolean("runAll", true),
                selectedId = o.optInt("selected", -1),
                startDelayMs = o.optLong("delay", 0L)
            )
        }
    }
}
