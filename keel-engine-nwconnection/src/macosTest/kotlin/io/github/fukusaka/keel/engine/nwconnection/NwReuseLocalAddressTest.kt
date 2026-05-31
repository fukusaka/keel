package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_create_tcp_params_with_options
import nwconnection.keel_nw_get_reuse_local_address
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam test pinning that every plain-TCP listener parameter the
 * NWConnection engine builds enables `reuse_local_address` — the
 * documented Network.framework analogue of `SO_REUSEADDR`.
 *
 * **Known limitation**: setting this flag is best-effort. On the
 * tested macOS, `NWListener` does **not** honour `reuse_local_address`
 * when binding over a local-port `TIME_WAIT` — it still fails with
 * `EADDRINUSE` (`NWListener failed to start (port=-1, errno=48)`), even
 * though a POSIX socket with `SO_REUSEADDR` *or* `SO_REUSEPORT` binds the
 * identical state. So a NWConnection server cannot immediately rebind a
 * port whose prior server-side connections linger in `TIME_WAIT`, unlike
 * the POSIX engines. This test only pins that the engine sets the
 * documented flag (the correct best-effort); the residual rebind
 * limitation is a known Network.framework bug (Apple Radar FB8658821) and
 * the failure now reports the errno.
 *
 * Synchronous param construction (no I/O / dispatch), so no timeout is
 * needed. Params are ARC-managed (the `.def` runs in Objective-C mode),
 * so they must not be manually released here.
 */
@OptIn(ExperimentalForeignApi::class)
class NwReuseLocalAddressTest {

    @Test
    fun `plain tcp listener params enable local address reuse`() {
        val params = keel_nw_create_tcp_params() ?: error("keel_nw_create_tcp_params returned null")
        assertEquals(
            1,
            keel_nw_get_reuse_local_address(params),
            "reuse_local_address (SO_REUSEADDR equivalent) must be set so NWListener can bind a TIME_WAIT port",
        )
    }

    @Test
    fun `tcp listener params with socket options enable local address reuse`() {
        val params = keel_nw_create_tcp_params_with_options(no_delay = 1, enable_keepalive = 1)
            ?: error("keel_nw_create_tcp_params_with_options returned null")
        assertEquals(
            1,
            keel_nw_get_reuse_local_address(params),
            "socket-option listener params must also enable reuse_local_address",
        )
    }
}
