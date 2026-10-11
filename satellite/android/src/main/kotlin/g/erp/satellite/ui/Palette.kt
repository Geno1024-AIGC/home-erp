package g.erp.satellite.ui

import android.content.res.Configuration
import android.graphics.Color

/**
 * Day/night colours for the satellite UI. Everything the app draws in code is
 * coloured from here, so switching themes is a matter of the framework picking
 * `values-night` resources and this class reading the same `uiMode` flag.
 *
 * The activity is not declared with `android:configChanges="uiMode"`, so the
 * system recreates it on a theme switch and a fresh [Palette] is built with it.
 */
class Palette(configuration: Configuration) {

    val isNight: Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Window / list backdrop. Matches the GEF page body colour. */
    val background: Int = pick(day = Color.rgb(240, 242, 245), night = Color.rgb(18, 18, 22))

    /** Header, drawer and card surfaces. */
    val surface: Int = pick(day = Color.WHITE, night = Color.rgb(33, 33, 38))

    val divider: Int = pick(day = Color.rgb(230, 232, 236), night = Color.rgb(58, 58, 64))

    val textPrimary: Int = pick(day = Color.rgb(30, 30, 30), night = Color.rgb(235, 235, 238))

    val textSecondary: Int = pick(day = Color.GRAY, night = Color.rgb(161, 161, 170))

    val textTertiary: Int = pick(day = Color.LTGRAY, night = Color.rgb(122, 122, 130))

    val error: Int = pick(day = Color.rgb(179, 38, 30), night = Color.rgb(242, 139, 130))

    val success: Int = pick(day = Color.rgb(76, 175, 80), night = Color.rgb(129, 199, 132))

    val failure: Int = pick(day = Color.rgb(229, 57, 53), night = Color.rgb(239, 135, 128))

    val scrim: Int = 0x66000000

    private fun pick(day: Int, night: Int): Int = if (isNight) night else day
}
