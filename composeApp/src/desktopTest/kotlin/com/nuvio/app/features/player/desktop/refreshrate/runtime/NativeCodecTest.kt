package com.nuvio.app.features.player.desktop.refreshrate.runtime

import com.nuvio.app.features.player.desktop.refreshrate.AT_240
import com.nuvio.app.features.player.desktop.refreshrate.FailureKind
import com.nuvio.app.features.player.desktop.refreshrate.MODE_100
import com.nuvio.app.features.player.desktop.refreshrate.MODE_240
import com.nuvio.app.features.player.desktop.refreshrate.Rational
import com.nuvio.app.features.player.desktop.refreshrate.SwitchOutcome
import com.nuvio.app.features.player.desktop.refreshrate.Timing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeCodecTest {
    private val state240 = longArrayOf(2560, 1440, 239901, 1000, 10, 1, 0)

    @Test
    fun `state decodes and rejects bad arrays`() {
        assertEquals(AT_240, NativeCodec.state(state240))
        assertNull(NativeCodec.state(null))
        assertNull(NativeCodec.state(longArrayOf(1, 2, 3)))
    }

    @Test
    fun `modes decode in groups of six and a ragged array is a failure`() {
        val modes = NativeCodec.modes(longArrayOf(2560, 1440, 239901, 1000, 10, 0, 2560, 1440, 10000, 100, 10, 0))
        assertEquals(listOf(MODE_240, MODE_100), modes)
        assertEquals(emptyList(), NativeCodec.modes(LongArray(0)))
        assertNull(NativeCodec.modes(null))
        assertNull(NativeCodec.modes(LongArray(7)))
    }

    @Test
    fun `switch results decode every failure code`() {
        assertEquals(SwitchOutcome.Ok(AT_240), NativeCodec.switchOutcome(longArrayOf(0, *state240, 312)))
        for (kind in FailureKind.entries) {
            val values = longArrayOf(kind.ordinal + 1L, *LongArray(NativeCodec.STATE_SIZE), 5)
            assertEquals(SwitchOutcome.Failed(kind), NativeCodec.switchOutcome(values), kind.code)
        }
        assertEquals(SwitchOutcome.Failed(FailureKind.UNEXPECTED_ERROR), NativeCodec.switchOutcome(null))
        assertEquals(SwitchOutcome.Failed(FailureKind.UNEXPECTED_ERROR), NativeCodec.switchOutcome(longArrayOf(99, *state240, 0)))
        assertEquals(SwitchOutcome.Failed(FailureKind.UNEXPECTED_ERROR), NativeCodec.switchOutcome(longArrayOf(0, 1)))
    }

    @Test
    fun `timing encodes for native`() {
        assertContentEquals(longArrayOf(2, 239901, 1000), NativeCodec.timing(Timing.DisplaySync(Rational(239901, 1000))))
        assertContentEquals(longArrayOf(1, 0, 0), NativeCodec.timing(Timing.Upstream))
        assertContentEquals(longArrayOf(0, 0, 0), NativeCodec.timing(null))
    }
}
