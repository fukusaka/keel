package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn

/**
 * One listen endpoint of a multi-address [StreamEngine.bindPipeline] call:
 * a bind [address] plus that address's own [config].
 *
 * Mixing plain and TLS listeners on one server is expressed here — pass a
 * TLS-capable [BindConfig] subclass (e.g. `TlsServerConfig`) as the [config]
 * of the TLS endpoints only. Note that "one address = one certificate" is
 * not implied: SNI-style multi-certificate selection belongs to the TLS
 * configuration of a single listener, not to additional bind addresses.
 *
 * Deliberately a plain class, not a `data class`: the type is expected to
 * grow optional fields (kept behind secondary constructors preserving this
 * shape) without freezing `copy`/`componentN` into the binary surface.
 */
class BindSpec(
    val address: SocketAddress,
    val config: BindConfig = BindConfig(),
)

/**
 * All-or-nothing bind loop shared by multi-address
 * [StreamEngine.bindPipeline] implementations.
 *
 * Binds every entry of [binds] in order via [bindOne]. When any bind fails,
 * every listener bound so far is closed again in reverse bind order via
 * [closeOne] and the original failure is rethrown; a close failure during
 * that rollback is logged to [logger], attached to the original failure as
 * a suppressed exception, and does not stop the rollback.
 *
 * @param L the engine's per-listener resource (fd wrapper, channel, ...).
 * @return one listener per [binds] entry, in bind order.
 * @throws IllegalArgumentException when [binds] is empty.
 */
fun <L> bindAllOrRollback(
    binds: List<BindSpec>,
    logger: Logger,
    closeOne: (L) -> Unit,
    bindOne: (BindSpec) -> L,
): List<L> {
    require(binds.isNotEmpty()) { "binds must not be empty" }
    val bound = ArrayList<L>(binds.size)
    for (spec in binds) {
        try {
            bound.add(bindOne(spec))
        } catch (cause: Throwable) {
            for (i in bound.indices.reversed()) {
                try {
                    closeOne(bound[i])
                } catch (closeFailure: Throwable) {
                    logger.warn(closeFailure) {
                        "multi-address bind rollback: closing the listener for ${binds[i].address} failed"
                    }
                    cause.addSuppressed(closeFailure)
                }
            }
            throw cause
        }
    }
    return bound
}
