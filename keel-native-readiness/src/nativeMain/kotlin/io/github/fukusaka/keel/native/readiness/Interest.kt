package io.github.fukusaka.keel.native.readiness

/**
 * The readiness a registration waits for on a file descriptor.
 *
 * Readiness engines register interest per `(fd, interest)` pair. How that maps
 * onto the kernel is the engine's business — kqueue arms one filter per pair,
 * epoll carries a combined event mask per fd — but callers above the engine
 * describe what they are waiting for in these terms.
 */
enum class Interest { READ, WRITE }
