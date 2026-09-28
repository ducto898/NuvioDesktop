package com.nuvio.app.features.player.desktop.refreshrate

// The owner's measured 2560x1440 modes (docs/research/02-mode-enumeration.md), 10 bpc, progressive.
internal fun qhd(num: Long, den: Long, bpc: Int = 10, interlaced: Boolean = false) =
    DisplayMode(2560, 1440, Rational(num, den), bpc, interlaced)

internal val MODE_280 = qhd(279961, 1000)
internal val MODE_240 = qhd(239901, 1000)
internal val MODE_144 = qhd(143973, 1000)
internal val MODE_120 = qhd(119998, 1000)
internal val MODE_100 = qhd(10000, 100)
internal val MODE_60 = qhd(59951, 1000)

/** Includes GDI's 59/60 alias as a duplicate entry. */
internal val OWNER_MODES = listOf(MODE_280, MODE_240, MODE_144, MODE_120, MODE_100, MODE_60, qhd(59951, 1000))

internal fun fhd(num: Long, den: Long) = DisplayMode(1920, 1080, Rational(num, den), 10)

internal val ORIG = DisplayState(MODE_280, hdr = true)
internal val AT_240 = DisplayState(MODE_240, hdr = true)
internal val AT_100 = DisplayState(MODE_100, hdr = true)

internal val SNAP_23 = FpsResult.Snapped(Rational(24000, 1001), FpsSource.CONTAINER, 23.976, 1.0)
internal val SNAP_25 = FpsResult.Snapped(Rational(25, 1), FpsSource.CONTAINER, 25.0, 0.0)

internal val SEL_240 = Selection.Switch(MODE_240, 10, 587.0, SNAP_23, 6)
internal val SEL_100 = Selection.Switch(MODE_100, 4, 0.0, SNAP_25, 6)
internal val SEL_ALREADY_240 = Selection.AlreadyAtTarget(MODE_240, 10, 587.0, SNAP_23, 6)
internal val SEL_NONE = Selection.NoSwitch("fps-missing", "container=null estimate=null")

internal val CTX = SessionContext(display = "A", target = MODE_240, original = ORIG, owner = 1)

internal fun start(
    playerId: Long,
    selection: Selection,
    display: String = "A",
    current: DisplayState = ORIG,
) = SessionEvent.PlaybackStart(playerId, display, current, selection)

internal fun switchOk(observed: DisplayState = AT_240) = SessionEvent.SwitchFinished(SwitchOutcome.Ok(observed))

internal fun switchFailed(kind: FailureKind) = SessionEvent.SwitchFinished(SwitchOutcome.Failed(kind))

/** Feeds events in order, returning every step. */
internal fun run(session: Session, vararg events: SessionEvent): List<Step> {
    var s = session
    return events.map { e -> RefreshRateSession.step(s, e).also { s = it.session } }
}

internal fun sessionIn(state: SessionState) = Session(state)
