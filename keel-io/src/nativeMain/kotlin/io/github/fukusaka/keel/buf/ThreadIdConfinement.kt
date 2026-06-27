package io.github.fukusaka.keel.buf

/**
 * Default [ConfinementToken] for thread-pinned allocators: the owner is the
 * thread that runs the first [BufferAllocator.allocate]. This is the right model
 * for the POSIX engines (epoll / kqueue / io_uring / nio), whose EventLoop is
 * pinned to one pthread.
 *
 * The owner is captured lazily, not at construction: the EventLoop's loop pthread
 * does not exist when `createChild` runs (that happens on the bootstrap thread),
 * so the id is [UNSET] until the first allocate latches it.
 *
 * Until the latch, [isCurrentContextOwner] returns `true` so any release takes the
 * freelist fast path — releases cannot precede the first allocate in practice, but
 * the guard keeps the not-yet-bound window well-defined.
 *
 * **Thread safety**: [ownerTid] is `@Volatile`; [captureOwner] is single-writer
 * (the owning thread, on its first allocate) and [isCurrentContextOwner] is a
 * read, so the latch publishes safely to any releasing thread.
 */
internal class ThreadIdConfinement : ConfinementToken {
    @kotlin.concurrent.Volatile
    private var ownerTid: Long = UNSET

    override fun captureOwner() {
        if (ownerTid == UNSET) ownerTid = currentThreadId()
    }

    override fun isCurrentContextOwner(): Boolean {
        val owner = ownerTid
        return owner == UNSET || currentThreadId() == owner
    }

    private companion object {
        private const val UNSET = -1L
    }
}
