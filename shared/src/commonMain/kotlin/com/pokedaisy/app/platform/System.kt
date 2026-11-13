@file:Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.pokedaisy.app.platform

/** printf-style formatting (String.format in the default locale on Android, as before). */
expect fun String.format(vararg args: Any?): String

/** A monotonic clock in nanoseconds (System.nanoTime on Android). */
expect fun nanoTime(): Long

/** The wall clock in milliseconds since 1970 (System.currentTimeMillis on Android). */
expect fun currentTimeMillis(): Long

/** The device clock's hour (0-23), minute (0-59) and month (1-12) in its own time zone. */
expect fun localHour(): Int
expect fun localMinute(): Int
expect fun localMonth(): Int

/** The platform log (android.util.Log on Android). */
expect object Log {
    fun d(tag: String, msg: String): Int
    fun i(tag: String, msg: String): Int
    fun w(tag: String, msg: String): Int
    fun w(tag: String, msg: String, tr: Throwable?): Int
    fun e(tag: String, msg: String): Int
    fun e(tag: String, msg: String, tr: Throwable?): Int
}

/** A language + region (java.util.Locale on Android). */
expect class Locale

expect val Locale.language: String
expect val Locale.country: String

/** The device's locale (Locale.getDefault() on Android). */
expect fun defaultLocale(): Locale

/** java.util.Random's generator (the class itself on Android), so a seed gives the same numbers everywhere. */
expect open class Random() {
    constructor(seed: Long)
}

expect fun Random.nextInt(bound: Int): Int
expect fun Random.nextDouble(): Double

/** [s] in Unicode normalization form D (accents split off their letters). */
expect fun normalizeNfd(s: String): String
