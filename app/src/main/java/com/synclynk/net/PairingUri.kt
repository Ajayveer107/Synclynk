package com.synclynk.net

/**
 * Parses the QR content shown on the Windows app:
 *   synclynk://<ip>:<port>/<token>
 */
data class PairingInfo(val ip: String, val port: Int, val token: String)

object PairingUri {
    private val REGEX = Regex("""^synclynk://([^:/\s]+):(\d+)/([A-Za-z0-9]+)$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): PairingInfo? {
        val cleaned = raw.trim().replace("\n", "").replace("\r", "")
        val match = REGEX.matchEntire(cleaned) ?: return null
        val (ip, port, token) = match.destructured
        return PairingInfo(ip = ip, port = port.toIntOrNull() ?: return null, token = token)
    }
}
