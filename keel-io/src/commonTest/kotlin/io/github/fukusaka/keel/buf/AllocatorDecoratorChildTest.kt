package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * What closing a decorator reaches.
 *
 * `createChild` and `createUntrackedChild` both promise the caller an instance
 * of its own, and the untracked one is the caller's to close. A decorator has to
 * keep both halves: hand back a distinct wrapper — the NWConnection transport
 * takes one per connection and confines it to that connection's queue — and,
 * when closed, give back whatever its delegate really made. What it must not do
 * is close a delegate it was merely handed: the interface lets a stateless
 * allocator answer a child request with itself, and then a caller doing exactly
 * what the contract says closes an allocator somebody else owns.
 *
 * So the rule is ownership, not identity: forward the close to a delegate that
 * produced a new child, and to nothing else.
 */
class AllocatorDecoratorChildTest {

    @Test
    fun `a decorator hands every caller its own instance`() {
        // Distinctness is what makes a per-connection child per-connection. A
        // decorator that answered with itself would collapse them onto one.
        for ((name, decorate) in decorators) {
            val decorated = decorate(SelfReturningAllocator())

            val first = decorated.createUntrackedChild()
            val second = decorated.createUntrackedChild()

            assertNotSame(decorated, first, "$name must not hand back itself")
            assertNotSame(first, second, "$name must not hand two callers the same child")
        }
    }

    @Test
    fun `closing a child of a self-returning allocator does not close it`() {
        for ((name, decorate) in decorators) {
            val delegate = SelfReturningAllocator()
            val decorated = decorate(delegate)

            decorated.createUntrackedChild().close()

            assertEquals(
                0,
                delegate.closes,
                "$name: its delegate made no child, so there was nothing of its own to close",
            )
        }
    }

    @Test
    fun `closing a child of a child-making allocator does close it`() {
        // The counterpart. A decorator that forwarded nothing would strand what
        // its delegate handed it — freelists and arena runs that only their own
        // close gives back.
        for ((name, decorate) in decorators) {
            val delegate = ChildMakingAllocator()
            val decorated = decorate(delegate)

            decorated.createUntrackedChild().close()

            assertEquals(1, delegate.childClosed, "$name must close the child its delegate made")
        }
    }

    @Test
    fun `closing a decorator a caller built does close what it wraps`() {
        // A chain a caller built is closed by closing its outermost wrapper:
        // `SlabAllocator().withTracking()` hands back the only reference there
        // is, so if that close stopped here the pool could never be given back.
        for ((name, decorate) in decorators) {
            val delegate = ChildMakingAllocator()

            decorate(delegate).close()

            assertEquals(1, delegate.closes, "$name: closing the chain has to reach the end of it")
        }
    }

    @Test
    fun `closing a child a caller built the decorator around still spares the caller's allocator`() {
        // Both halves at once: the caller built the wrapper, and then something
        // derived a child from it. Closing that child must not reach past the
        // allocator the caller configured, which its delegate answered with.
        for ((name, decorate) in decorators) {
            val delegate = SelfReturningAllocator()

            decorate(delegate).createUntrackedChild().close()

            assertEquals(0, delegate.closes, "$name: the child was never a child")
        }
    }

    /** Stateless: its children are itself, which the interface allows. */
    private class SelfReturningAllocator : BufferAllocator by DefaultAllocator {
        var closes = 0
            private set

        override fun createChild(): BufferAllocator = this

        override fun createUntrackedChild(): BufferAllocator = this

        override fun close() {
            closes++
        }
    }

    /** Pooled-shaped: every child is a new instance the caller must close. */
    private class ChildMakingAllocator : BufferAllocator by DefaultAllocator {
        var childClosed = 0
            private set
        var closes = 0
            private set

        override fun createChild(): BufferAllocator = Child(this)

        override fun createUntrackedChild(): BufferAllocator = createChild()

        override fun close() {
            closes++
        }

        private class Child(private val parent: ChildMakingAllocator) : BufferAllocator by DefaultAllocator {
            override fun close() {
                parent.childClosed++
            }
        }
    }

    private companion object {
        /** Every decorator in the module that wraps children of its own. */
        val decorators: List<Pair<String, (BufferAllocator) -> BufferAllocator>> = listOf(
            "TrackingAllocator" to { delegate -> TrackingAllocator(delegate) },
            "LeakDetectingAllocator" to { delegate -> LeakDetectingAllocator(delegate) { } },
            "ProfilingAllocator" to { delegate -> ProfilingAllocator(delegate) },
        )
    }
}
