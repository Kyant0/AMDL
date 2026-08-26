package com.kyant.amdl.api

import org.json.JSONArray
import org.json.JSONObject

typealias JsonObject = JSONObject
typealias JsonArray = JSONArray

inline fun buildJsonObject(block: JsonObject.() -> Unit): JsonObject {
    return JsonObject().apply(block)
}

fun JsonObject.getObjectOrNull(name: String): JsonObject? {
    return opt(name) as? JsonObject
}

fun JsonObject.getArrayOrNull(name: String): JsonArray? {
    return opt(name) as? JsonArray
}

fun JsonObject.getStringOrNull(name: String): String? {
    return opt(name)?.toString()
}

fun JsonObject.getBooleanOrNull(name: String): Boolean? {
    val value = opt(name) ?: return null
    return when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
}

fun JsonObject.getIntOrNull(name: String): Int? {
    val value = opt(name) ?: return null
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

fun JsonArray.getObjectOrNull(index: Int): JsonObject? {
    return opt(index) as? JsonObject
}

fun <T> JsonArray.asSequence(): Sequence<T> {
    return object : Sequence<T> {

        override fun iterator(): Iterator<T> {
            return object : Iterator<T> {
                var index = 0

                override fun hasNext(): Boolean {
                    return index < length()
                }

                override fun next(): T {
                    if (!hasNext()) throw NoSuchElementException()
                    @Suppress("UNCHECKED_CAST")
                    return opt(index++) as T
                }
            }
        }
    }
}
