/*
 * EV Insight Lab -Tube — modifications to AABrowser (GPL-3.0-or-later).
 * Added 2026-11-03: motion lock for the car screen.
 *
 * On the car screen the picture is covered while the car is moving; the page keeps
 * running underneath, so audio carries on. The lock fails closed: once movement has been
 * seen, video only returns after a fresh GPS fix shows the car has stayed stopped for
 * STOPPED_HOLD_MS. Without location permission, or with Location switched off, the car
 * screen stays covered. On the phone's own screen (not connected to the car) nothing is
 * locked, so passengers can watch normally.
 */
package com.kododake.aabrowser.ev

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display

class DriveSafeMonitor(
    private val activity: Activity,
    private val onStateChanged: (State) -> Unit
) : LocationListener {

    enum class State {
        /** Not on the car screen: no lock. */
        OFF,
        /** On the car screen and stopped (or no movement seen yet): video allowed. */
        PARKED,
        /** On the car screen and moving: picture covered, audio continues. */
        MOVING,
        /** On the car screen without location permission: picture covered. */
        NEEDS_PERMISSION,
        /** On the car screen with Location switched off: picture covered. */
        LOCATION_OFF
    }

    var state: State = State.OFF
        private set

    private val locationManager = activity.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var movementSeen = false
    private var lastMovingAt = 0L
    private var lastFixAt = 0L
    private var lastFixKmh = 0f

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            if (listening) handler.postDelayed(this, 2_000L)
        }
    }

    fun isCarScreen(): Boolean {
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        if (displayId != Display.DEFAULT_DISPLAY) return true
        val uiType = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiType == Configuration.UI_MODE_TYPE_CAR) return true
        val uiModeManager = activity.getSystemService(UiModeManager::class.java)
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }

    fun hasLocationPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestLocationPermission() {
        activity.requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            EvConfig.REQUEST_CODE_DRIVE_SAFE_LOCATION
        )
    }

    /** Call from onResume and after permission changes. */
    fun start() {
        stopListening()
        if (!isCarScreen()) {
            setState(State.OFF)
            return
        }
        if (!hasLocationPermission()) {
            setState(State.NEEDS_PERMISSION)
            return
        }
        if (locationManager == null || !locationManager.isLocationEnabled) {
            setState(State.LOCATION_OFF)
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { locationManager.allProviders.contains(it) }.getOrDefault(false) }
        var registered = false
        for (provider in providers) {
            try {
                locationManager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (!registered) {
            setState(State.LOCATION_OFF)
            return
        }
        listening = true
        handler.post(tick)
        evaluate()
    }

    /** Call from onPause / onDestroy. */
    fun stop() {
        stopListening()
    }

    private fun stopListening() {
        if (listening) {
            runCatching { locationManager?.removeUpdates(this) }
        }
        listening = false
        handler.removeCallbacks(tick)
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val now = SystemClock.elapsedRealtime()
        val kmh = location.speed * 3.6f
        lastFixAt = now
        lastFixKmh = kmh
        if (kmh >= EvConfig.MOVING_KMH) {
            movementSeen = true
            lastMovingAt = now
        } else if (kmh >= EvConfig.STOPPED_KMH) {
            // Creeping in traffic: still counts as moving once movement has been seen.
            if (movementSeen) lastMovingAt = now
        }
        evaluate()
    }

    override fun onProviderDisabled(provider: String) {
        if (locationManager?.isLocationEnabled == false) setState(State.LOCATION_OFF)
    }

    override fun onProviderEnabled(provider: String) {
        if (listening) evaluate() else start()
    }

    private fun evaluate() {
        if (!listening) return
        if (!movementSeen) {
            setState(State.PARKED)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val fixFresh = now - lastFixAt <= EvConfig.FIX_STALE_MS
        val stoppedLongEnough = now - lastMovingAt >= EvConfig.STOPPED_HOLD_MS
        val parked = fixFresh && lastFixKmh < EvConfig.STOPPED_KMH && stoppedLongEnough
        setState(if (parked) State.PARKED else State.MOVING)
        if (parked) movementSeen = false
    }

    private fun setState(newState: State) {
        if (newState == state) return
        state = newState
        onStateChanged(newState)
    }
}
