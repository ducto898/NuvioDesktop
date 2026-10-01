package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal val pluginDispatcher: CoroutineDispatcher = Dispatchers.Default

// nuvio-rr fork, Phase 9 #7: the same evaluation timeout as Android (a while(true) scraper held its thread forever).
internal fun QuickJs.configurePluginRuntime() {
    evaluationTimeoutMillis = PLUGIN_TIMEOUT_MS
}
