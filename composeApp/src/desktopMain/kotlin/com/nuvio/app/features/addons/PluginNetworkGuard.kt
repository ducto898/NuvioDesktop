package com.nuvio.app.features.addons

import java.net.InetAddress

/**
 * nuvio-rr fork, Phase 9 S2. Which addresses a plugin request may connect to. Loopback, any-local, link-local
 * (incl. 169.254.169.254) and multicast never; private LAN ranges (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7)
 * only with NUVIO_PLUGINS_ALLOW_LAN=1. Checked on the address actually connected to, for every redirect hop.
 */
internal object PluginNetworkGuard {
    val allowLanByEnv: Boolean = System.getenv("NUVIO_PLUGINS_ALLOW_LAN") == "1"

    fun isAllowed(address: InetAddress, allowLan: Boolean = allowLanByEnv): Boolean = TODO("Phase 9 S2 commit B")
}
