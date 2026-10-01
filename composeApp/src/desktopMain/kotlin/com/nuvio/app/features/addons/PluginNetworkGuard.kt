package com.nuvio.app.features.addons

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.Interceptor

/**
 * nuvio-rr fork, Phase 9 S2. Which addresses a plugin request may connect to. Loopback, any-local, link-local
 * (incl. 169.254.169.254) and multicast never; private LAN ranges (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7)
 * only with NUVIO_PLUGINS_ALLOW_LAN=1. Checked on the address actually connected to, for every redirect hop.
 */
internal object PluginNetworkGuard {
    val allowLanByEnv: Boolean = System.getenv("NUVIO_PLUGINS_ALLOW_LAN") == "1"

    fun isAllowed(address: InetAddress, allowLan: Boolean = allowLanByEnv): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        val bytes = address.address
        val cgnat = bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64
        val uniqueLocalV6 = address is Inet6Address && (bytes[0].toInt() and 0xFE) == 0xFC
        val lan = address.isSiteLocalAddress || cgnat || uniqueLocalV6
        return !lan || allowLan
    }

    /**
     * Network interceptor: runs once per hop (redirects included) after the connection is made and before the request
     * is written, on the address actually connected to (so DNS answers and IP literals are both covered).
     */
    val interceptor = Interceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address == null || !isAllowed(address)) {
            throw IOException("blocked: plugin request to a local or private address (${chain.request().url.host})")
        }
        chain.proceed(chain.request())
    }
}
