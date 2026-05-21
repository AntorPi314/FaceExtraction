package com.antor.face.extraction.utils

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREF_NAME = "face_extraction_prefs"
    private const val KEY_INTERVAL = "capture_interval_seconds"
    private const val KEY_USE_FRONT_CAMERA = "use_front_camera"
    private const val KEY_SERVER_PORT = "server_port"

    const val DEFAULT_INTERVAL = 15
    const val DEFAULT_USE_FRONT_CAMERA = false
    const val DEFAULT_PORT = 8080

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getCaptureInterval(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setCaptureInterval(context: Context, seconds: Int) =
        prefs(context).edit().putInt(KEY_INTERVAL, seconds).apply()

    fun getUseFrontCamera(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_FRONT_CAMERA, DEFAULT_USE_FRONT_CAMERA)

    fun setUseFrontCamera(context: Context, useFront: Boolean) =
        prefs(context).edit().putBoolean(KEY_USE_FRONT_CAMERA, useFront).apply()

    fun getServerPort(context: Context): Int =
        prefs(context).getInt(KEY_SERVER_PORT, DEFAULT_PORT)

    fun setServerPort(context: Context, port: Int) =
        prefs(context).edit().putInt(KEY_SERVER_PORT, port).apply()
}
