package com.spectroflac.analysis

import java.util.Locale

/**
 * Every number the app prints is a technical measurement, so formatting is pinned to
 * Locale.US — "44.1KHZ" must not turn into "44,1KHZ" on a French phone.
 */
fun String.fmt(vararg args: Any?): String = String.format(Locale.US, this, *args)
