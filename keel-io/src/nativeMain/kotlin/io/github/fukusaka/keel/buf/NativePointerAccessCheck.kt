package io.github.fukusaka.keel.buf

/**
 * Checks that the buffers an engine will read through can be handed to a
 * syscall, and throws naming [this] when they cannot.
 *
 * A Native engine reaches a read buffer's memory through [unsafePointer], whose
 * cast is unchecked because it runs on every read. An allocator whose buffers do
 * not implement [NativePointerAccess] therefore fails at the first byte read
 * rather than at the point it was configured: the cast throws inside a readiness
 * dispatch, the containment ends that connection, and what a user sees is every
 * connection dying with a `ClassCastException` in a log. Asked once while the
 * engine is being built, the same mistake says which allocator is wrong before
 * anything is served.
 *
 * **The readiness engines ask; the others do not.** Their shared base calls this
 * as it is constructed, so epoll and kqueue ask and anything built on it asks
 * without having to remember to. The other
 * Native engines take the same pointer and do not yet, and neither do the JVM
 * and JS ones, which cast to their own backing types. Extending it is tracked;
 * until then a user who moves a misconfigured allocator from one engine to
 * another meets the failure in a different form — and not always the same form.
 * On io_uring a ring-capable kernel reads into its own buffer ring and touches
 * the configured allocator only under back-pressure, so there the cast fails
 * under load rather than on every connection.
 *
 * **It asks the allocator it was given, not a child of it.** An engine reads
 * through children, so asking one of those would be the sharper question — and
 * it is not worth what it costs. A child has to be closed by whoever derived it,
 * and no caller can tell whether what `createChild` handed back is new or the
 * allocator it already had: the interface's default answer is the receiver
 * itself, and a wrapper forwards that answer outward. Every rule for telling the
 * two apart is written in terms of identity, and identity is not ownership, so
 * each one has a hole where somebody's allocator gets closed mid-construction.
 * Asking the root has no such hole: it makes no child, so there is nothing whose
 * owner anybody has to work out.
 *
 * It costs something in both directions. An allocator whose root deals in native
 * buffers while its children do not passes and should not; one whose root
 * refuses to allocate at all — a pure factory, with the pooling in its
 * children — is refused and should not be, and what leaves is that allocator's
 * own failure rather than a refusal naming an engine. Neither is a shape
 * anything here hands out, and the sharper question was never much sharper:
 * children may differ from each other too, so asking one of them would not have
 * covered the rest. What either version rules out is the whole class of
 * allocators that do not deal in native buffers at all, which is the mistake
 * worth a name.
 *
 * **Ask at the size reads will use.** A pooled allocator can build a small
 * buffer and a large one through different seams — keel's own carves cached
 * classes from a chunk and allocates anything above the cache cap outright — so
 * a one-byte probe attests only the seam it happened to take. Callers pass the
 * size they read at. A per-bind or per-connect override that crosses the cache
 * boundary is still unattested; it fails per operation, like every other buffer
 * this never saw.
 *
 * **It speaks for read buffers only.** The write path takes the same pointer
 * from whatever the application handed to the pipeline — a slice, a wrapped
 * `ByteArray`, a buffer from another allocator entirely — and nothing checked
 * here can attest to those. They keep failing per operation, which is where they
 * belong: they arrive one at a time, from callers this has never seen.
 *
 * What it leaves behind is one allocate and one release. Whether an observer
 * counting them sees a pair depends on which thread built the engine, which the
 * paragraph on confinement below sets out. Two things outlast them on a pooled
 * allocator, both measured rather than assumed.
 *
 * The first is a chunk. A read-sized probe is a pooled class, so it carves, and
 * carving an empty arena commits a chunk — 256 KiB on keel's own, still resident
 * afterwards and not given back by a trim, which keeps one idle chunk in reserve
 * (a probe above the cache cap allocates outright instead and leaves nothing,
 * which is why the residue depends on the size an engine reads at). One chunk per
 * shard it lands on: engines built one after another against a shared allocator
 * share the first, since the probe before them released into the freelist the
 * probe after them pops, while engines built at the same time carve on separate
 * shards and commit one apiece.
 *
 * That arena is the one the engine's own children carve from, so for an engine
 * that goes on to serve anything the chunk is warm-up: measured, a serving engine
 * ends up with the same chunk count either way. For one built and closed without
 * serving it is residue — 256 KiB per construction, measured — and the remedy
 * that would clear it, closing the allocator, is only open to someone holding
 * one. A default configuration builds its own root inside itself, so in the
 * common case nobody can. Building engines in order to discard them, which is
 * mostly what tests do, therefore accumulates.
 *
 * The second is the root's confinement, which latches to the thread that built
 * the engine: a pooled allocator captures its owner on the first allocation, and
 * an unlatched root answers every thread as its owner. Engines read through
 * children, whose confinement is untouched, so this reaches only a caller who
 * allocates from the root itself. Those are rare but they are not new — the
 * in-memory engine keel ships for tests hands a root straight to its transports
 * and copies through it on every flush — so a root may or may not already be
 * latched when this runs, and both cases are worth setting out.
 *
 * On a root nothing has allocated from yet, the probe latches it and its release
 * takes the freelist path: allocate and release recorded as a pair. On a root
 * already latched to another thread — a second engine built on a second thread
 * from the same shared allocator, or a caller who allocated from it first — the
 * release routes to the cross-thread queue instead, measured, and the counter
 * that reports the cross-thread rate counts it. The buffer is not stranded: the
 * queue drains on the root's next miss, its trim, or its close. The release
 * *event* waits for that drain though, and an engine that reads through children
 * never allocates from its root again, so in practice it waits until close —
 * which, under a default configuration, is a terminus nobody can reach. Until
 * then the root reports one more allocation than release, and a leak reporter
 * driven by the lifecycle listener, asked to report inside that window, names
 * this probe. The report is spurious
 * and the configuration it appears on is the correct one, which is reason enough
 * to say so here rather than leave it to be found.
 *
 * Both counts are the allocator's cumulative ones, plain increments documented as
 * lossy when more than one thread writes them, so engines built concurrently
 * against a shared allocator report slightly fewer than they made.
 *
 * One more thing an observer sees rather than keeps: an allocator that counts
 * pool hits and misses records the probe as a miss, since a root's freelist is
 * empty the first time anything asks. A profile dumped before traffic arrives
 * therefore shows that size class at one miss and no hits — or, where an
 * allocator is shared, at one miss and a hit for every engine built after the
 * first, each popping what the one before it released.
 *
 * It is also one allocation the engine's own read path would have prefaced with
 * a size hint. A pooled allocator may route a hinted class differently from an
 * un-hinted one, so what this attests is the un-hinted seam at that size.
 *
 * A refusal is what a caller gets when the buffers are wrong. A probe that could
 * not be released is attached to it rather than raised in its place, and raised
 * on its own when there is no refusal to attach it to. An allocator that will
 * not allocate at all raises its own failure, unwrapped: that is its answer to a
 * question this asked plainly, and dressing it as a refusal would say the
 * buffers are wrong when nothing was ever handed out.
 *
 * @param who names the engine for the message, so a process with more than one
 *   says which is refusing.
 * @param readBufferSize the size this engine reads at.
 */
fun BufferAllocator.requireNativePointerAccess(who: String, readBufferSize: Int) {
    val probe = allocate(readBufferSize)
    // Nothing stands between the allocation and the release but the type test,
    // which cannot throw, so there is no window to reason about. The message is
    // composed afterwards because it can be: the probe stays referenced past its
    // release, and naming its class needs the reference rather than the buffer.
    // (`simpleName` answers null for a class the runtime cannot name, which the
    // fallback below covers; on this target it does not throw -- an instance's
    // `::class` is a `KClassImpl`, and the throwing one the runtime also has is
    // built for class literals the compiler could not reflect.)
    val accepted = probe is NativePointerAccess
    val releaseFailure = runCatching { probe.release() }.exceptionOrNull()
    if (accepted) {
        if (releaseFailure != null) throw releaseFailure
        return
    }
    throw IllegalArgumentException(
        "$who cannot read through ${describeForRefusal()}: it allocates ${probe.describeForRefusal()}, " +
            "which does not implement NativePointerAccess, and this engine passes read-buffer memory " +
            "straight to syscalls. Configure an allocator whose buffers do — defaultAllocator() is " +
            "one — or implement NativePointerAccess on the ones this hands out.",
    ).also { refusal -> if (releaseFailure != null) refusal.addSuppressed(releaseFailure) }
}

/**
 * A name for the message. Anonymous objects have no `simpleName`, and "null" in
 * a refusal names nothing.
 */
private fun Any.describeForRefusal(): String = this::class.simpleName ?: "an anonymous ${this::class}"
