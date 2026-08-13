package com.rfsat.bas.environment

/**
 * One place that knows whether the instruments are actually talking.
 *
 * A prone shooter finds out that the Kestrel dropped by getting a wind
 * correction that is quietly stale — the number is still on the screen, it
 * still looks like a measurement, and nothing about it says it is four
 * minutes old. This exists so a glance can say otherwise.
 *
 * The three states matter separately, and STALE is the reason for the class.
 * A link that is CONNECTED but silent is the dangerous case: "connected" is
 * what the Bluetooth stack reports, and it keeps reporting it long after the
 * device has stopped sending anything useful. So a link that has gone quiet
 * for longer than [STALE_AFTER_MS] is reported as stale no matter what the
 * stack thinks.
 */
object LinkStatus {

    enum class Kind(val label: String) { KESTREL("Kestrel"), RANGEFINDER("LRF"), CAMERA("Cam") }

    enum class State { OFFLINE, CONNECTING, LIVE, STALE }

    /** Quiet for this long and a live link is no longer trustworthy. The
     *  Kestrel pushes readings every few seconds; a rangefinder only speaks
     *  when ranged, so it is given far longer. */
    private const val STALE_AFTER_MS = 30_000L
    private const val STALE_AFTER_MS_LRF = 10 * 60_000L

    data class Link(val state: State, val detail: String, val lastDataAtMs: Long)

    private val links = linkedMapOf(
        Kind.KESTREL to Link(State.OFFLINE, "", 0L),
        Kind.RANGEFINDER to Link(State.OFFLINE, "", 0L),
        Kind.CAMERA to Link(State.OFFLINE, "", 0L)
    )

    @Synchronized
    fun set(kind: Kind, state: State, detail: String = "") {
        val prev = links[kind]
        links[kind] = Link(
            state = state,
            detail = detail,
            // Only real data refreshes the clock. Connecting and disconnecting
            // are not data, and must not make a silent link look alive.
            lastDataAtMs = if (state == State.LIVE) System.currentTimeMillis()
                           else prev?.lastDataAtMs ?: 0L
        )
    }

    /** Records that data actually arrived — the only thing that clears
     *  staleness. */
    fun dataArrived(kind: Kind, detail: String = "") = set(kind, State.LIVE, detail)

    fun offline(kind: Kind, detail: String = "") = set(kind, State.OFFLINE, detail)

    @Synchronized
    fun get(kind: Kind): Link {
        val l = links[kind] ?: return Link(State.OFFLINE, "", 0L)
        if (l.state != State.LIVE) return l
        val limit = if (kind == Kind.RANGEFINDER) STALE_AFTER_MS_LRF else STALE_AFTER_MS
        val age = System.currentTimeMillis() - l.lastDataAtMs
        return if (age > limit) l.copy(state = State.STALE) else l
    }

    private fun symbol(s: State) = when (s) {
        State.LIVE -> "●"          // filled: sending
        State.STALE -> "◐"         // half: connected but silent
        State.CONNECTING -> "◌"    // hollow: trying
        State.OFFLINE -> "○"       // empty: nothing
    }

    /** One compact line for the Range-mode glance, e.g. "Kestrel ●  LRF ◐".
     *  Links that were never used at all are left out — a shooter with no
     *  rangefinder does not need to be told so on every screen. */
    fun chip(): String = Kind.entries
        .map { it to get(it) }
        .filter { (_, l) -> l.state != State.OFFLINE || l.lastDataAtMs > 0L }
        .joinToString("   ") { (k, l) -> "${k.label} ${symbol(l.state)}" }

    /** True when something that WAS working has stopped — the case worth
     *  colouring red rather than merely printing. */
    fun anyStale(): Boolean = Kind.entries.any { get(it).state == State.STALE }
}
