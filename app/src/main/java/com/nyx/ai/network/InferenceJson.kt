package com.nyx.ai.network

import org.json.JSONObject
import org.json.JSONTokener

/** Require one complete object, not an array, JSON string, or trailing second document. */
internal fun parseJsonObject(text: String): JSONObject {
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    require(value is JSONObject && tokener.nextClean() == '\u0000') { "Expected one JSON object." }
    return value
}

/** Missing/null/malformed counters stay unknown; zero is a valid reported measurement. */
internal fun JSONObject.nonnegativeLong(name: String): Long? {
    val number = opt(name) as? Number ?: return null
    val value = number.toLong()
    return value.takeIf { it >= 0 && number.toDouble() == it.toDouble() }
}
