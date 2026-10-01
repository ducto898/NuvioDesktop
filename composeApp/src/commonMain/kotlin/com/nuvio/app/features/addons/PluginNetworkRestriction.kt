package com.nuvio.app.features.addons

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * nuvio-rr fork, Phase 9 S2. Marks an HTTP request made for a scraper plugin. The desktop [httpRequestRaw] then
 * refuses connections to this PC and to private networks; addon requests (which may be self-hosted) are unchanged.
 */
object PluginNetworkRestriction : AbstractCoroutineContextElement(PluginNetworkRestrictionKey)

object PluginNetworkRestrictionKey : CoroutineContext.Key<PluginNetworkRestriction>
