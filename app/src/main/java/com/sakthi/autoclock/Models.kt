package com.sakthi.autoclock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Step(
    val type: String,            // "TAP" or "TAP_TODAY"
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val x: Int,
    val y: Int,
    val delayMs: Long,
    var optional: Boolean,
    val x2: Int = 0,
    val y2: Int = 0,
    val durMs: Long = 0
) {
    fun hasIdentity() = !text.isNullOrBlank() || !desc.isNullOrBlank() || !viewId.isNullOrBlank()

    fun label(): String = when {
        type == "SWIPE" -> "swipe ($x,$y) to ($x2,$y2)"
        type == "TAP_TODAY" -> "tap today's date"
        !text.isNullOrBlank() -> "tap \"$text\""
        !desc.isNullOrBlank() -> "tap \"$desc\""
        !viewId.isNullOrBlank() -> "tap $viewId"
        else -> "tap ($x,$y)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("viewId", viewId ?: "").put("text", text ?: "")
        .put("desc", desc ?: "").put("x", x).put("y", y)
        .put("delayMs", delayMs).put("optional", optional)
        .put("x2", x2).put("y2", y2).put("durMs", durMs)

    companion object {
        fun fromJson(o: JSONObject) = Step(
            o.getString("type"),
            o.optString("viewId").ifBlank { null },
            o.optString("text").ifBlank { null },
            o.optString("desc").ifBlank { null },
            o.optInt("x"), o.optInt("y"),
            o.optLong("delayMs", 800), o.optBoolean("optional"),
            o.optInt("x2"), o.optInt("y2"), o.optLong("durMs")
        )
    }
}

data class Guard(val text: String, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("message", message)
    companion object {
        fun fromJson(o: JSONObject) = Guard(o.getString("text"), o.getString("message"))
    }
}

data class Automation(
    val id: String,
    var name: String,
    var hour: Int,
    var minute: Int,
    var days: List<Int>,                       // Calendar.MONDAY(2)..SATURDAY(7), SUNDAY=1
    var targetPackage: String = "",
    var enabled: Boolean = true,
    val steps: MutableList<Step> = mutableListOf(),
    val guards: MutableList<Guard> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("hour", hour).put("minute", minute)
        .put("days", JSONArray(days)).put("targetPackage", targetPackage)
        .put("enabled", enabled)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("guards", JSONArray(guards.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): Automation {
            val d = o.getJSONArray("days")
            val s = o.getJSONArray("steps")
            val g = o.getJSONArray("guards")
            return Automation(
                o.getString("id"), o.getString("name"), o.getInt("hour"), o.getInt("minute"),
                List(d.length()) { d.getInt(it) },
                o.optString("targetPackage"), o.optBoolean("enabled", true),
                MutableList(s.length()) { Step.fromJson(s.getJSONObject(it)) },
                MutableList(g.length()) { Guard.fromJson(g.getJSONObject(it)) }
            )
        }
    }
}

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("autoclock", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Automation> {
        val arr = JSONArray(prefs(c).getString("data", "[]"))
        return MutableList(arr.length()) { Automation.fromJson(arr.getJSONObject(it)) }
    }

    fun get(c: Context, id: String): Automation? = all(c).firstOrNull { it.id == id }

    fun save(c: Context, a: Automation) {
        val l = all(c)
        val i = l.indexOfFirst { it.id == a.id }
        if (i >= 0) l[i] = a else l.add(a)
        write(c, l)
    }

    fun delete(c: Context, id: String) = write(c, all(c).filter { it.id != id })

    private fun write(c: Context, l: List<Automation>) {
        prefs(c).edit().putString("data", JSONArray(l.map { it.toJson() }).toString()).apply()
    }
}
