package com.dmahony.e220chat

import kotlinx.serialization.json.*

internal val E220Json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun JsonObject.optString(name: String, default: String = ""): String {
    return this[name]?.jsonPrimitive?.contentOrNull ?: default
}

internal fun JsonObject.optInt(name: String, default: Int = 0): Int {
    return this[name]?.jsonPrimitive?.intOrNull ?: default
}

internal fun JsonObject.optLong(name: String, default: Long = 0L): Long {
    return this[name]?.jsonPrimitive?.longOrNull ?: default
}

internal fun JsonObject.optDouble(name: String, default: Double = 0.0): Double {
    return this[name]?.jsonPrimitive?.doubleOrNull ?: default
}

internal fun JsonObject.optBooleanFlexible(name: String, default: Boolean = false): Boolean {
    val primitive = this[name]?.jsonPrimitive ?: return default
    primitive.booleanOrNull?.let { return it }
    primitive.intOrNull?.let { return it != 0 }
    primitive.contentOrNull?.let { text ->
        return text.equals("true", ignoreCase = true) || text == "1"
    }
    return default
}

internal fun JsonElement?.asRawString(): String {
    return when (this) {
        null, JsonNull -> "{}"
        else -> toString()
    }
}

internal val JsonPrimitive.contentOrNull: String?
    get() = if (isString || booleanOrNull != null || intOrNull != null || longOrNull != null || doubleOrNull != null) content else null
