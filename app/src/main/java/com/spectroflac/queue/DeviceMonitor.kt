package com.spectroflac.queue

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone reports about heat and power, for the queue to adapt to. */
class DeviceMonitor(private val context: Context) {

    data class State(
        /** `PowerManager.THERMAL_STATUS_*`. */
        val thermalStatus: Int = Parallelism.THERMAL_NONE,
        val batterySaver: Boolean = false,
        val batteryPercent: Int = 100,
        val charging: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private var started = false

    /** Registers the listeners for the life of the process; they are cheap and fire rarely. */
    fun start() {
        if (started) return
        started = true
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        runCatching {
            power.addThermalStatusListener(context.mainExecutor) { status ->
                _state.value = _state.value.copy(thermalStatus = status)
            }
            _state.value = _state.value.copy(thermalStatus = power.currentThermalStatus)
        }

        _state.value = _state.value.copy(batterySaver = power.isPowerSaveMode)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    _state.value = _state.value.copy(batterySaver = power.isPowerSaveMode)
                }
            },
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )

        val battery = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = update(intent)
        }
        // A sticky broadcast: registering returns the current value right away.
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::update)
    }

    private fun update(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        _state.value = _state.value.copy(batteryPercent = percent, charging = charging)
    }
}
