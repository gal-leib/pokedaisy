@file:Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.pokedaisy.app.platform

/** A map safe to share between threads (java.util.concurrent.ConcurrentHashMap on Android). */
expect open class ConcurrentHashMap<K, V>() : MutableMap<K, V>

expect fun <K, V> ConcurrentHashMap<K, V>.putIfAbsent(key: K, value: V): V?

/** Atomic on Android, as Kotlin's ConcurrentMap.getOrPut: a value two threads race to put is put once. */
expect inline fun <K, V> ConcurrentHashMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V

/** A thread-safe queue (java.util.concurrent.ConcurrentLinkedQueue on Android). */
expect open class ConcurrentLinkedQueue<E>() : MutableCollection<E>

expect fun <E> ConcurrentLinkedQueue<E>.offer(e: E): Boolean
expect fun <E> ConcurrentLinkedQueue<E>.poll(): E?

/** A set safe to share between threads (a ConcurrentHashMap's key set on Android). */
expect fun <T> concurrentSet(): MutableSet<T>

/** A map that compares keys by identity (java.util.IdentityHashMap on Android). */
expect fun <K, V> identityHashMap(): MutableMap<K, V>

/** A heap ordered by [comparator] (java.util.PriorityQueue on Android: ties come out in the same order). */
expect open class PriorityQueue<E>(comparator: Comparator<in E>) : MutableCollection<E>

expect fun <E> PriorityQueue<E>.poll(): E?

/** Runs [block] holding [lock]'s monitor (Kotlin's own synchronized on Android). */
expect inline fun <R> synchronized(lock: Any, block: () -> R): R

/** Something to run (java.lang.Runnable on Android). */
expect fun interface Runnable {
    fun run()
}

/** Runs tasks (java.util.concurrent.Executor on Android). */
expect fun interface Executor {
    fun execute(command: Runnable)
}

/** One background thread running tasks in order, as a daemon named [name]. */
expect fun singleThreadExecutor(name: String): Executor

/** Starts [block] on a new daemon thread named [name]. */
expect fun startDaemonThread(name: String, block: () -> Unit)
