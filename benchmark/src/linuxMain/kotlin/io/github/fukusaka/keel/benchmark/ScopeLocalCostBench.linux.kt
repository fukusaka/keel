package io.github.fukusaka.keel.benchmark

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

private class LinBox(var n: Long)

// Old HttpHeadersPool.linux shape: a direct @ThreadLocal val (no generic HashMap).
@ThreadLocal
private val oldLinuxStack = LinBox(0)

private val linuxScopeSlot: ScopeLocal<LinBox> = scopeLocal { LinBox(0) }

private class LinOut(val ns: DoubleArray)

private const val LIN_ITERS = 200_000_000
private const val LIN_WARMUP = 20_000_000
private const val LIN_PATHS = 3

/**
 * Linux actual for [runScopeLocalCostBench]: measures `ScopeLocal.current()` on
 * a raw pthread (the epoll / io_uring EventLoop execution context). On Linux
 * `scopeLocal` resolves to the generic `@ThreadLocal`-HashMap-backed
 * `ThreadLocalScopeLocal`. Compared against the old direct `@ThreadLocal` val
 * (what HttpHeadersPool.linux used before the ScopeLocal migration) and a
 * caller-cached reference (the floor the per-connection caller-cache achieves —
 * HttpRequestDecoder resolves the stack once and reuses it).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun runScopeLocalCostBench(): Boolean {
    val out = DoubleArray(LIN_PATHS) { -1.0 }
    val arena = Arena()
    try {
        val threadPtr = arena.alloc<pthread_tVar>()
        val ref = StableRef.create(LinOut(out))
        pthread_create(
            threadPtr.ptr, null,
            staticCFunction { arg ->
                val o = arg!!.asStableRef<LinOut>().get()
                var sink = 0L
                val cached = linuxScopeSlot.current()
                for (i in 0 until LIN_WARMUP) {
                    sink += linuxScopeSlot.current().n
                    sink += oldLinuxStack.n
                    sink += cached.n
                }
                val t0 = TimeSource.Monotonic.markNow()
                for (i in 0 until LIN_ITERS) sink += linuxScopeSlot.current().n
                o.ns[0] = t0.elapsedNow().inWholeNanoseconds.toDouble() / LIN_ITERS
                val t1 = TimeSource.Monotonic.markNow()
                for (i in 0 until LIN_ITERS) sink += oldLinuxStack.n
                o.ns[1] = t1.elapsedNow().inWholeNanoseconds.toDouble() / LIN_ITERS
                val t2 = TimeSource.Monotonic.markNow()
                for (i in 0 until LIN_ITERS) sink += cached.n
                o.ns[2] = t2.elapsedNow().inWholeNanoseconds.toDouble() / LIN_ITERS
                if (sink == Long.MIN_VALUE) println("")
                arg.asStableRef<LinOut>().dispose()
                null
            },
            ref.asCPointer(),
        )
        pthread_join(threadPtr.ptr[0], null)
    } finally {
        arena.clear()
    }
    println("=== ScopeLocal.current() cost on a raw pthread (linux epoll/io_uring context), release ===")
    println("  scopeLocal{}.current() (ThreadLocalScopeLocal, @ThreadLocal HashMap): ${linFmt(out[0])} ns/call")
    println("  old direct @ThreadLocal val (pre-migration HttpHeadersPool.linux)    : ${linFmt(out[1])} ns/call")
    println("  caller-cached (resolved once, held — the decoder's per-conn cache)   : ${linFmt(out[2])} ns/call")
    println("  -> per-lookup migration cost (scopeLocal - old)  ~ ${linFmt(out[0] - out[1])} ns/call")
    println("  -> caller-cache removes (scopeLocal - cached)    ~ ${linFmt(out[0] - out[2])} ns/call")
    return true
}

private fun linFmt(v: Double): String {
    val scaled = (v * 100).toLong()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}
