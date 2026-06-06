package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.apple.DispatchQueueLocal
import io.github.fukusaka.keel.scope.ScopeLocal
import io.github.fukusaka.keel.scope.scopeLocal
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.TimeSource
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

private class Box(var n: Long)

// Old-HttpHeadersPool-style baseline: DispatchQueueLocal over a *direct*
// @ThreadLocal val (no generic HashMap). Isolates dispatch_get_specific cost.
@ThreadLocal
private val oldStyleStack = Box(0)

private val compositeSlot: ScopeLocal<Box> = scopeLocal { Box(0) }

@OptIn(ExperimentalForeignApi::class)
private val oldStyleSlot = DispatchQueueLocal<Box>(fallback = { oldStyleStack })

private class Out(val ns: DoubleArray)

private const val ITERS = 200_000_000
private const val WARMUP = 20_000_000

/**
 * macOS actual for [runScopeLocalCostBench]. See common KDoc for purpose and
 * methodology. Runs the three loops on a raw pthread (kqueue context) and
 * prints ns/call plus the dispatch_get_specific / HashMap / caller-cache
 * breakdown.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun runScopeLocalCostBench(): Boolean {
    val out = DoubleArray(3) { -1.0 }
    val arena = Arena()
    try {
        val threadPtr = arena.alloc<pthread_tVar>()
        val ref = StableRef.create(Out(out))
        pthread_create(
            threadPtr.ptr, null,
            staticCFunction { arg ->
                val o = arg!!.asStableRef<Out>().get()
                var sink = 0L
                // warmup all paths (also primes the per-pthread slots)
                val cached = compositeSlot.current()
                for (i in 0 until WARMUP) {
                    sink += compositeSlot.current().n
                    sink += oldStyleSlot.current().n
                    sink += cached.n
                }
                val t0 = TimeSource.Monotonic.markNow()
                for (i in 0 until ITERS) sink += compositeSlot.current().n
                o.ns[0] = t0.elapsedNow().inWholeNanoseconds.toDouble() / ITERS
                val t1 = TimeSource.Monotonic.markNow()
                for (i in 0 until ITERS) sink += oldStyleSlot.current().n
                o.ns[1] = t1.elapsedNow().inWholeNanoseconds.toDouble() / ITERS
                val t2 = TimeSource.Monotonic.markNow()
                for (i in 0 until ITERS) sink += cached.n
                o.ns[2] = t2.elapsedNow().inWholeNanoseconds.toDouble() / ITERS
                if (sink == Long.MIN_VALUE) println("")
                arg.asStableRef<Out>().dispose()
                null
            },
            ref.asCPointer(),
        )
        pthread_join(threadPtr.ptr[0], null)
    } finally {
        arena.clear()
    }
    println("=== ScopeLocal.current() cost on a raw pthread (kqueue context), release ===")
    println("  composite (scopeLocal: DQL over @ThreadLocal HashMap): ${fmt(out[0])} ns/call")
    println("  dql + @ThreadLocal val (HttpHeadersPool.apple shape)  : ${fmt(out[1])} ns/call")
    println("  caller-cached (resolved once, held)                   : ${fmt(out[2])} ns/call")
    println("  -> dispatch_get_specific miss   (composite - cached - hashmap) ~ ${fmt(out[1] - out[2])} ns/call")
    println("  -> generic @ThreadLocal HashMap (composite - dql+val)          ~ ${fmt(out[0] - out[1])} ns/call")
    println("  -> total per-read overhead removed by caller-cache (composite - cached) ~ ${fmt(out[0] - out[2])} ns/call")
    return true
}

private fun fmt(v: Double): String {
    val scaled = (v * 100).toLong()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
