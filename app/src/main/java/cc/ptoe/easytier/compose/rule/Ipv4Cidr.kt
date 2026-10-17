package cc.ptoe.easytier.compose.rule

import java.net.Inet4Address

/**
 * Minimal IPv4 CIDR value object used by the LAN auto start/stop rule.
 *
 * Kept free of Android types so the matching logic is unit-testable. Parsing is
 * strict (dotted quad + prefix length) and never resolves host names, which also
 * makes it safe to run from a broadcast receiver on a cold start.
 */
@ConsistentCopyVisibility
data class Ipv4Cidr private constructor(
    /** Network address with the host bits cleared, as a big-endian int. */
    val network: Int,
    /** Prefix length, 0..32. */
    val prefix: Int,
) {
    private val mask: Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

    /** True when [address] belongs to this network. */
    fun contains(address: Int): Boolean = (address and mask) == network

    /** True when [address] belongs to this network. */
    fun contains(address: Inet4Address): Boolean = contains(address.toInt())

    /** Canonical `a.b.c.d/prefix` form, used by the settings UI and logs. */
    override fun toString(): String = "${formatAddress(network)}/$prefix"

    companion object {
        /** Parses `a.b.c.d/prefix`, or returns null when the text is not a valid IPv4 CIDR. */
        fun parseOrNull(raw: String): Ipv4Cidr? {
            val text = raw.trim()
            val slash = text.indexOf('/')
            if (slash <= 0 || slash == text.length - 1) return null
            val address = parseAddress(text.substring(0, slash)) ?: return null
            val prefix = text.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            return Ipv4Cidr(address and mask, prefix)
        }

        /** Parses a dotted-quad IPv4 address into a big-endian int. */
        fun parseAddress(raw: String): Int? {
            val parts = raw.trim().split('.')
            if (parts.size != 4) return null
            var value = 0
            for (part in parts) {
                if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet
            }
            return value
        }
    }
}

private fun formatAddress(value: Int): String =
    "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

internal fun Inet4Address.toInt(): Int {
    val bytes = address
    return ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
}
