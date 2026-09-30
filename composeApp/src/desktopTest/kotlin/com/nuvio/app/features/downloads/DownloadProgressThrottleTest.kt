package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadProgressThrottleTest {
    private val ms = 1_000_000L

    @Test
    fun first_read_is_reported() {
        assertTrue(DownloadProgressThrottle().shouldReport(0L))
    }

    @Test
    fun reads_inside_the_interval_are_not_reported() {
        val throttle = DownloadProgressThrottle(intervalNanos = 250 * ms)
        assertTrue(throttle.shouldReport(0L))
        assertFalse(throttle.shouldReport(1 * ms))
        assertFalse(throttle.shouldReport(249 * ms))
        assertTrue(throttle.shouldReport(250 * ms))
        assertFalse(throttle.shouldReport(300 * ms))
    }

    @Test
    fun a_fast_download_reports_about_four_times_a_second() {
        // 100 Mbit/s = ~1526 reads of 8 KB per second, over 10 s
        val throttle = DownloadProgressThrottle()
        val reads = 15_260
        val reported = (0 until reads).count { i -> throttle.shouldReport(i * (10_000L * ms / reads)) }
        assertEquals(40, reported)
    }
}
