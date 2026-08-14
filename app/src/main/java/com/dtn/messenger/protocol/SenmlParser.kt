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

                if (obj.has("bn")) baseName = obj.getString("bn")
                if (obj.has("bt")) baseTime = obj.getDouble("bt")
                if (obj.has("bu")) baseUnit = obj.getString("bu")

                val n = if (obj.has("n")) obj.getString("n") else null
                val fullName = baseName + (n ?: "")

                if (fullName.isEmpty()) continue

                val hasValue = obj.has("v") || obj.has("vs") || obj.has("vb") || obj.has("vd")
                if (!hasValue && n == null) {
                    continue
                }

                val u = if (obj.has("u")) obj.getString("u") else baseUnit

                val valueStr =
                    when {
                        obj.has("v") -> formatDouble(obj.getDouble("v"))
                        obj.has("vs") -> obj.getString("vs")
                        obj.has("vb") -> obj.getBoolean("vb").toString()
                        obj.has("vd") -> obj.getString("vd")
                        else -> "0"
                    }

                val t = if (obj.has("t")) obj.getDouble("t") else 0.0
                val timeVal = calculateTimeMs(baseTime + t, fallbackTimestampMs)

                result.add(SenmlRecord(name = fullName, value = valueStr, unit = u, timestamp = timeVal))
            }
        } catch (e: Exception) {
            // Parsing failed
        }
        return result
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

                val bnObj = item.get("bn") ?: item.get(-2)
                if (bnObj != null) baseName = bnObj.AsString()

                val btObj = item.get("bt") ?: item.get(-3)
                if (btObj != null) baseTime = btObj.AsDouble()

                val buObj = item.get("bu") ?: item.get(-4)
                if (buObj != null) baseUnit = buObj.AsString()

                val nObj = item.get("n") ?: item.get(0)
                val n = nObj?.AsString()
                val fullName = baseName + (n ?: "")

                if (fullName.isEmpty()) continue

                val uObj = item.get("u") ?: item.get(1)
                val u = uObj?.AsString() ?: baseUnit

                val vObj = item.get("v") ?: item.get(2)
                val vsObj = item.get("vs") ?: item.get(3)
                val vbObj = item.get("vb") ?: item.get(4)
                val vdObj = item.get("vd") ?: item.get(8)

                if (vObj == null && vsObj == null && vbObj == null && vdObj == null && n == null) {
                    continue
                }

                val valueStr =
                    when {
                        vObj != null -> formatDouble(vObj.AsDouble())
                        vsObj != null -> vsObj.AsString()
                        vbObj != null -> vbObj.AsBoolean().toString()
                        vdObj != null -> vdObj.AsString()
                        else -> "0"
                    }

                val tObj = item.get("t") ?: item.get(6)
                val t = tObj?.AsDouble() ?: 0.0
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
        if (timeSec <= 0) return fallbackTimestampMs
        return if (timeSec > 268435456.0) {
            (timeSec * 1000.0).toLong()
        } else {
            fallbackTimestampMs + (timeSec * 1000.0).toLong()
        }
    }
}
