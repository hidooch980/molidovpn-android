package com.molido.vpn

import org.json.JSONObject
import java.util.Locale

/**
 * Bounded mKCP settings supported by the pinned Xray 26.10.31+ core.
 *
 * Legacy header/seed obfuscation is intentionally not translated here: current
 * Xray moved that behavior to FinalMask. Unknown legacy masks fail closed.
 */
internal data class KcpProfile(
    val mtu: Int = 1350,
    val tti: Int = 50,
    val uplinkCapacity: Int = 5,
    val downlinkCapacity: Int = 20,
    val cwndMultiplier: Double = 1.0,
    val maxSendingWindow: Int = 2_097_152,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mtu", mtu)
        put("tti", tti)
        put("uplinkCapacity", uplinkCapacity)
        put("downlinkCapacity", downlinkCapacity)
        put("cwndMultiplier", cwndMultiplier)
        put("maxSendingWindow", maxSendingWindow)
    }

    companion object {
        private const val MIN_MTU = 576
        private const val MAX_MTU = 1460
        private const val MIN_TTI = 10
        private const val MAX_TTI = 100
        private const val MAX_CAPACITY_MBPS = 1000
        private const val MAX_CWND_MULTIPLIER = 64.0
        private const val MAX_SENDING_WINDOW = 16 * 1024 * 1024

        fun fromQuery(query: Map<String, String>): KcpProfile? {
            val profile = KcpProfile(
                mtu = query.intValue("mtu", 1350) ?: return null,
                tti = query.intValue("tti", 50) ?: return null,
                uplinkCapacity = query.intValue("uplinkcapacity", 5) ?: return null,
                downlinkCapacity = query.intValue("downlinkcapacity", 20) ?: return null,
                cwndMultiplier = query.doubleValue("cwndmultiplier", 1.0) ?: return null,
                maxSendingWindow = query.intValue("maxsendingwindow", 2_097_152) ?: return null,
            )
            return profile.takeIf { it.isValid() }
        }

        fun fromJson(json: JSONObject?): KcpProfile? {
            if (json == null) return KcpProfile()
            val header = json.optJSONObject("header")
            val headerType = header?.optString("type").orEmpty().lowercase(Locale.US)
            if (headerType.isNotEmpty() && headerType != "none") return null
            if (json.optString("seed").isNotBlank()) return null
            val profile = KcpProfile(
                mtu = json.intValue("mtu", 1350) ?: return null,
                tti = json.intValue("tti", 50) ?: return null,
                uplinkCapacity = json.intValue("uplinkCapacity", 5) ?: return null,
                downlinkCapacity = json.intValue("downlinkCapacity", 20) ?: return null,
                cwndMultiplier = json.doubleValue("cwndMultiplier", 1.0) ?: return null,
                maxSendingWindow = json.intValue("maxSendingWindow", 2_097_152) ?: return null,
            )
            return profile.takeIf { it.isValid() }
        }
    }

    private fun isValid(): Boolean =
        mtu in MIN_MTU..MAX_MTU &&
            tti in MIN_TTI..MAX_TTI &&
            uplinkCapacity in 0..MAX_CAPACITY_MBPS &&
            downlinkCapacity in 0..MAX_CAPACITY_MBPS &&
            cwndMultiplier.isFinite() &&
            cwndMultiplier in 1.0..MAX_CWND_MULTIPLIER &&
            maxSendingWindow in mtu..MAX_SENDING_WINDOW
}

private fun Map<String, String>.intValue(key: String, default: Int): Int? {
    if (!containsKey(key)) return default
    return this[key]?.takeIf { it.isNotBlank() }?.toIntOrNull()
}

private fun Map<String, String>.doubleValue(key: String, default: Double): Double? {
    if (!containsKey(key)) return default
    return this[key]?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
}

private fun JSONObject.intValue(key: String, default: Int): Int? {
    if (!has(key) || isNull(key)) return default
    return opt(key)?.toString()?.toIntOrNull()
}

private fun JSONObject.doubleValue(key: String, default: Double): Double? {
    if (!has(key) || isNull(key)) return default
    return opt(key)?.toString()?.toDoubleOrNull()
}
