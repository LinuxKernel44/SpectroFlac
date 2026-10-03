package com.spectroflac.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.spectroflac.queue.AppNotifications

/** What the app is currently allowed to do in the background. */
data class PermissionStatus(val notifications: Boolean, val battery: Boolean) {
    val allGranted: Boolean get() = notifications && battery
}

object Permissions {
    fun status(context: Context) = PermissionStatus(
        notifications = AppNotifications.canNotify(context),
        battery = batteryUnrestricted(context),
    )

    /** True once Android is told not to put this app's background work to sleep. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android 13 and later ask at runtime; before that notifications are on unless switched off in Settings. */
    val needsRuntimeNotificationPermission: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The system dialog "Allow SpectroFlac to always run in the background?". */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Opens the exemption dialog, or the general battery list on a device that has no such dialog. */
    fun requestBatteryExemption(context: Context) {
        runCatching { context.startActivity(batteryExemptionIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(batterySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun openNotificationSettings(context: Context) {
        runCatching { context.startActivity(notificationSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The permission status, re-read every time the app comes back to the screen (the user may have used Settings). */
@Composable
fun rememberPermissionStatus(): State<PermissionStatus> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Permissions.status(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = Permissions.status(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
