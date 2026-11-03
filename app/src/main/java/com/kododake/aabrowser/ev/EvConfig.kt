/*
 * EV Insight Lab -Tube — modifications to AABrowser (GPL-3.0-or-later).
 * Added 2026-11-03: central settings for updates, sharing and safety.
 */
package com.kododake.aabrowser.ev

object EvConfig {
    /** GitHub Pages site that hosts the download page and the update manifest. */
    const val SITE_URL = "https://sheranr.github.io/ev-insight-lab-tube/"

    /** Update manifest written by the release workflow. */
    const val OTA_URL = SITE_URL + "ota/version.json"

    const val SOURCE_URL = "https://github.com/sheranr/ev-insight-lab-tube"

    const val YOUTUBE_HOME = "https://m.youtube.com"

    /** Automatic update checks run at most this often (manual checks always run). */
    const val AUTO_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000

    // Drive-safe thresholds. GPS speed is noisy, so moving and stopped use different limits.
    const val MOVING_KMH = 8f
    const val STOPPED_KMH = 3f

    /** How long the car must stay stopped before video comes back on the car screen. */
    const val STOPPED_HOLD_MS = 60_000L

    /** A fix older than this is treated as "no current GPS". */
    const val FIX_STALE_MS = 10_000L

    const val REQUEST_CODE_DRIVE_SAFE_LOCATION = 1104
}
