package org.json

class JSONObject() {
    private val map = linkedMapOf<String, Any?>()

    constructor(raw: String) : this() {
        parseInto(raw.trim())
    }

    private fun parseInto(trimmed: String) {
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= body.length || body[i] != '"') break
            val keyEnd = findStringEnd(body, i + 1)
            val key = body.substring(i + 1, keyEnd)
            i = keyEnd + 1
            while (i < body.length && (body[i].isWhitespace() || body[i] == ':')) i++
            if (i >= body.length) break
            val (value, nextIdx) = parseValue(body, i)
            map[key] = value
            i = nextIdx
        }
    }

    fun put(name: String, value: Any?): JSONObject { map[name] = value; return this }
    fun remove(name: String): Any? = map.remove(name)
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = !map.containsKey(name) || map[name] == null || map[name] === NULL
    fun opt(name: String): Any? = map[name]
    fun get(name: String): Any = map[name] ?: throw RuntimeException("Missing $name")
    fun getString(name: String): String = map[name]?.toString() ?: throw RuntimeException("Missing $name")
    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String = map[name]?.takeUnless { it === NULL }?.toString() ?: fallback
    fun getBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String): Boolean = optBoolean(name, false)
    fun optBoolean(name: String, fallback: Boolean): Boolean = (map[name] as? Boolean) ?: map[name]?.toString()?.toBooleanStrictOrNull() ?: fallback
    fun getInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String): Int = optInt(name, 0)
    fun optInt(name: String, fallback: Int): Int = (map[name] as? Number)?.toInt() ?: map[name]?.toString()?.toIntOrNull() ?: fallback
    fun getLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String): Long = optLong(name, 0L)
    fun optLong(name: String, fallback: Long): Long = (map[name] as? Number)?.toLong() ?: map[name]?.toString()?.toLongOrNull() ?: fallback
    fun getDouble(name: String): Double = optDouble(name, 0.0)
    fun optDouble(name: String): Double = optDouble(name, 0.0)
    fun optDouble(name: String, fallback: Double): Double = (map[name] as? Number)?.toDouble() ?: map[name]?.toString()?.toDoubleOrNull() ?: fallback
    fun getJSONObject(name: String): JSONObject = (map[name] as? JSONObject) ?: throw RuntimeException("Missing JSONObject $name")
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun getJSONArray(name: String): JSONArray = (map[name] as? JSONArray) ?: throw RuntimeException("Missing JSONArray $name")
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray
    fun keys(): Iterator<String> = map.keys.iterator()
    override fun toString(): String = map.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":${renderJsonValue(v)}"
    }
    companion object {
        @JvmField
        val NULL: Any = object {
            override fun toString(): String = "null"
        }
    }
}

class JSONArray() {
    internal val items = mutableListOf<Any?>()
    constructor(collection: Collection<*>) : this() {
        items.addAll(collection)
    }
    constructor(raw: String) : this() {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            var i = 0
            while (i < body.length) {
                while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
                if (i >= body.length) break
                val (v, nextIdx) = parseValue(body, i)
                items += v
                i = nextIdx
            }
        }
    }
    fun length(): Int = items.size
    fun put(value: Any?): JSONArray { items += value; return this }
    fun opt(index: Int): Any? = items.getOrNull(index)
    fun getString(index: Int): String = items[index]?.toString() ?: ""
    fun optString(index: Int): String = optString(index, "")
    fun optString(index: Int, fallback: String): String = items.getOrNull(index)?.toString() ?: fallback
    fun optJSONObject(index: Int): JSONObject? = items.getOrNull(index) as? JSONObject
    fun getJSONObject(index: Int): JSONObject = items[index] as JSONObject
    override fun toString(): String = items.joinToString(",", "[", "]") { renderJsonValue(it) }
}

internal fun findStringEnd(s: String, start: Int): Int {
    var i = start
    while (i < s.length) {
        if (s[i] == '\\') { i += 2; continue }
        if (s[i] == '"') return i
        i++
    }
    return s.length
}

internal fun findMatchingBracket(s: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = start
    while (i < s.length) {
        val c = s[i]
        if (c == '"') {
            i = findStringEnd(s, i + 1) + 1
            continue
        }
        if (c == open) depth++
        else if (c == close) {
            depth--
            if (depth == 0) return i + 1
        }
        i++
    }
    return s.length
}

internal fun parseValue(s: String, start: Int): Pair<Any?, Int> {
    val c = s[start]
    return when {
        c == '"' -> {
            val end = findStringEnd(s, start + 1)
            val str = s.substring(start + 1, end).replace("\\\"", "\"").replace("\\\\", "\\")
            str to (end + 1)
        }
        c == '{' -> {
            val end = findMatchingBracket(s, start, '{', '}')
            JSONObject(s.substring(start, end)) to end
        }
        c == '[' -> {
            val end = findMatchingBracket(s, start, '[', ']')
            JSONArray(s.substring(start, end)) to end
        }
        else -> {
            var end = start
            while (end < s.length && s[end] != ',' && s[end] != '}' && s[end] != ']') end++
            val token = s.substring(start, end).trim()
            val v: Any? = when {
                token == "null" -> JSONObject.NULL
                token == "true" -> true
                token == "false" -> false
                token.contains('.') -> token.toDoubleOrNull() ?: token
                else -> token.toLongOrNull() ?: token
            }
            v to end
        }
    }
}

internal fun renderJsonValue(v: Any?): String = when {
    v == null || v === JSONObject.NULL -> "null"
    v is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    v is Boolean || v is Number || v is JSONObject || v is JSONArray -> v.toString()
    else -> "\"$v\""
}
