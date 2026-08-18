package com.dtn.messenger.protocol

import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import org.json.JSONArray
import org.json.JSONObject

data class SenmlRecord(
    val name: String,
    val value: String,
    val unit: String,
    val timestamp: Long,
)

object SenmlParser {
    fun parse(
        data: ByteArray,
        fallbackTimestampMs: Long = System.currentTimeMillis(),
    ): List<SenmlRecord> {
        val text =
            try {
                String(data, Charsets.UTF_8).trim()
            } catch (e: Exception) {
                ""
            }

        if (text.startsWith("[") || text.startsWith("{")) {
            val recordsFromJson = parseJson(text, fallbackTimestampMs)
            if (recordsFromJson.isNotEmpty()) return recordsFromJson
        }

        val recordsFromCbor = parseCbor(data, fallbackTimestampMs)
        if (recordsFromCbor.isNotEmpty()) return recordsFromCbor

        if (text.isNotEmpty()) {
            val recordsFromJson = parseJson(text, fallbackTimestampMs)
            if (recordsFromJson.isNotEmpty()) return recordsFromJson
        }

        return emptyList()
    }

    private fun parseJson(
        jsonStr: String,
        fallbackTimestampMs: Long,
    ): List<SenmlRecord> {
        val result = mutableListOf<SenmlRecord>()
        try {
            val jsonArray =
                if (jsonStr.startsWith("[")) {
                    JSONArray(jsonStr)
                } else if (jsonStr.startsWith("{")) {
                    JSONArray().put(JSONObject(jsonStr))
                } else {
                    return emptyList()
                }

            var baseName = ""
            var baseTime = 0.0
            var baseUnit = ""

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue

                if (obj.has("bn") && !obj.isNull("bn")) baseName = obj.optString("bn", "")
                if (obj.has("bt") && !obj.isNull("bt")) baseTime = obj.optDouble("bt", 0.0)
                if (obj.has("bu") && !obj.isNull("bu")) baseUnit = obj.optString("bu", "")

                val n = if (obj.has("n") && !obj.isNull("n")) obj.optString("n", "") else null
                val fullName = baseName + (n ?: "")

                if (fullName.isEmpty()) continue

                val hasValue =
                    (obj.has("v") && !obj.isNull("v")) ||
                        (obj.has("vs") && !obj.isNull("vs")) ||
                        (obj.has("vb") && !obj.isNull("vb")) ||
                        (obj.has("vd") && !obj.isNull("vd"))

                if (!hasValue && n == null) {
                    continue
                }

                val u = if (obj.has("u") && !obj.isNull("u")) obj.optString("u", baseUnit) else baseUnit

                val valueStr =
                    when {
                        obj.has("v") && !obj.isNull("v") -> formatDouble(obj.optDouble("v", 0.0))
                        obj.has("vs") && !obj.isNull("vs") -> obj.optString("vs", "")
                        obj.has("vb") && !obj.isNull("vb") -> obj.optBoolean("vb", false).toString()
                        obj.has("vd") && !obj.isNull("vd") -> obj.optString("vd", "")
                        else -> "0"
                    }

                val t = if (obj.has("t") && !obj.isNull("t")) obj.optDouble("t", 0.0) else 0.0
                val timeVal = calculateTimeMs(baseTime + t, fallbackTimestampMs)

                result.add(SenmlRecord(name = fullName, value = valueStr, unit = u, timestamp = timeVal))
            }
        } catch (e: Exception) {
            // Parsing failed
        }
        return result
    }

    private fun getCborString(
        item: CBORObject,
        strKey: String,
        intKey: Int,
    ): String? {
        val obj = item.get(strKey) ?: item.get(intKey) ?: return null
        if (obj.isNull) return null
        return try {
            when (obj.type) {
                CBORType.TextString -> obj.AsString()
                CBORType.ByteString -> obj.GetByteString().toString(Charsets.UTF_8)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getCborDouble(
        item: CBORObject,
        strKey: String,
        intKey: Int,
    ): Double? {
        val obj = item.get(strKey) ?: item.get(intKey) ?: return null
        if (obj.isNull) return null
        return try {
            when (obj.type) {
                CBORType.FloatingPoint -> obj.AsDouble()
                CBORType.Integer -> obj.AsInt64Value().toDouble()
                else -> if (obj.isNumber) obj.AsString().toDoubleOrNull() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getCborBoolean(
        item: CBORObject,
        strKey: String,
        intKey: Int,
    ): Boolean? {
        val obj = item.get(strKey) ?: item.get(intKey) ?: return null
        if (obj.isNull) return null
        return try {
            if (obj.type == CBORType.Boolean) {
                obj.AsBoolean()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseCbor(
        data: ByteArray,
        fallbackTimestampMs: Long,
    ): List<SenmlRecord> {
        val result = mutableListOf<SenmlRecord>()
        try {
            val cbor = CBORObject.DecodeFromBytes(data) ?: return emptyList()
            val list =
                when (cbor.type) {
                    CBORType.Array -> cbor.values
                    CBORType.Map -> listOf(cbor)
                    else -> return emptyList()
                }

            var baseName = ""
            var baseTime = 0.0
            var baseUnit = ""

            for (item in list) {
                if (item.type != CBORType.Map) continue

                val bn = getCborString(item, "bn", -2)
                if (bn != null) {
                    baseName = bn
                }

                val bt = getCborDouble(item, "bt", -3)
                if (bt != null) {
                    baseTime = bt
                }

                val bu = getCborString(item, "bu", -4)
                if (bu != null) {
                    baseUnit = bu
                }

                val n = getCborString(item, "n", 0)
                val fullName = baseName + (n ?: "")

                if (fullName.isEmpty()) continue

                val u = getCborString(item, "u", 1) ?: baseUnit

                val v = getCborDouble(item, "v", 2)
                val vs = getCborString(item, "vs", 3)
                val vb = getCborBoolean(item, "vb", 4)

                val vdObj = item.get("vd") ?: item.get(8)
                val vd =
                    if (vdObj != null && !vdObj.isNull) {
                        if (vdObj.type == CBORType.ByteString) {
                            com.dtn.messenger.util.PayloadUtils.base64Encode(vdObj.GetByteString())
                        } else if (vdObj.type == CBORType.TextString) {
                            vdObj.AsString()
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                if (v == null && vs == null && vb == null && vd == null && n == null) {
                    continue
                }

                val valueStr =
                    when {
                        v != null -> formatDouble(v)
                        vs != null -> vs
                        vb != null -> vb.toString()
                        vd != null -> vd
                        else -> "0"
                    }

                val t = getCborDouble(item, "t", 6) ?: 0.0
                val timeVal = calculateTimeMs(baseTime + t, fallbackTimestampMs)

                result.add(SenmlRecord(name = fullName, value = valueStr, unit = u, timestamp = timeVal))
            }
        } catch (e: Exception) {
            // Parsing failed
        }
        return result
    }

    private fun formatDouble(d: Double): String {
        return if (d == d.toLong().toDouble()) {
            d.toLong().toString()
        } else {
            d.toString()
        }
    }

    private fun calculateTimeMs(
        timeSec: Double,
        fallbackTimestampMs: Long,
    ): Long {
        return if (timeSec > 268435456.0) {
            (timeSec * 1000.0).toLong()
        } else {
            fallbackTimestampMs + (timeSec * 1000.0).toLong()
        }
    }
}
