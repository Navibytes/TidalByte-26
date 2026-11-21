package com.tidalbyte.stridesync

import android.util.Log

/**
 * All app logs use the single tag "StrideSync", so `adb logcat -s StrideSync:*` shows everything. The
 * component name goes in brackets at the start of the message.
 */
object SLog {
    const val TAG = "StrideSync"

    fun d(component: String, msg: String) {
        Log.d(TAG, "[$component] $msg")
    }

    fun i(component: String, msg: String) {
        Log.i(TAG, "[$component] $msg")
    }

    fun w(component: String, msg: String, t: Throwable? = null) {
        Log.w(TAG, "[$component] $msg", t)
    }

    fun e(component: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$component] $msg", t)
    }
}
