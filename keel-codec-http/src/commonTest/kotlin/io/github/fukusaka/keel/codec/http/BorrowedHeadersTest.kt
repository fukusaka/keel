package io.github.fukusaka.keel.codec.http

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * That the borrow a decoder parses into is never one the pool has handed on,
 * and is never one the decoder forgets to give back.
 *
 * These are the two halves the decoders got wrong when the ownership record
 * lived in them: an ending returned the accumulator while the parse frame ran
 * on, and a replacement claimed after the ending had no second ending to
 * return it. Both are properties of the borrow, so they are checked here
 * rather than only through a decoder.
 */
class BorrowedHeadersTest {

    @AfterTest
    fun emptyThePool() {
        HttpHeadersPool.clear()
    }

    private fun primedPool(count: Int) {
        HttpHeadersPool.clear()
        val primed = List(count) { HttpHeaders.borrow() }
        for (instance in primed) instance.release()
    }

    @Test
    fun `an instance another borrower took is not handed out here`() {
        // The hazard the record exists for. Recycling puts the instance back;
        // taking it straight off again is fine, because nobody else has it.
        // What must not happen is handing out one that someone else now holds
        // -- the shape that let a second connection's request carry a header
        // this one had parsed.
        primedPool(2)
        val slot = BorrowedHeaders()
        slot.get()
        slot.recycle()

        val takenByAnother = HttpHeaders.borrow()

        assertNotSame(takenByAnother, slot.get(), "the other borrower keeps what it took")
        takenByAnother.release()
    }

    @Test
    fun `the same instance is handed out until it is given back`() {
        primedPool(2)
        val slot = BorrowedHeaders()

        assertSame(slot.get(), slot.get(), "a held borrow is not replaced on every access")
    }

    @Test
    fun `a transferred instance is not handed to the next caller`() {
        primedPool(2)
        val slot = BorrowedHeaders()

        val emitted = slot.transfer()

        assertNotSame(emitted, slot.get(), "the message keeps what it was given")

        // And what it was given came from the pool. An instance built outside
        // it satisfies the assertion above while never going back, because
        // `HttpHeaders.release` returns early on one that was never pooled --
        // which is how a message ends up owning something the pool will have
        // to replace.
        HttpHeadersPool.clear()
        emitted.release()
        assertEquals(1, HttpHeadersPool.size(), "and it is a pooled instance")
    }

    @Test
    fun `an ending gives the borrow back`() {
        primedPool(2)
        val slot = BorrowedHeaders()
        slot.get()

        slot.recycle()

        assertEquals(2, HttpHeadersPool.size(), "the pool is whole again")
    }

    @Test
    fun `a message emitted after an ending leaves the slot empty`() {
        // Why the ending needs no bookkeeping of its own. A borrow is taken on
        // first use and handed to the message at `transfer`, so a read that
        // completes its message hands the borrow away and holds nothing -- the
        // pool ends where it started, with no record of the ending involved.
        primedPool(2)
        val slot = BorrowedHeaders()
        slot.get()
        slot.recycle()

        repeat(5) {
            slot.get()
            slot.transfer().release()
        }

        assertEquals(2, HttpHeadersPool.size(), "every message gave back what it took")
    }

    @Test
    fun `a part-parsed message keeps the fields already in its accumulator`() {
        // The accumulator carries a header block that straddles a read
        // boundary. Giving it back between reads would discard the fields
        // parsed so far, and the decoder would frame what follows against the
        // headers it has left: measured through the decoders, a
        // `Content-Length` split across the boundary was lost, the body was
        // framed away, and its bytes were delivered as a request of their own.
        primedPool(2)
        val slot = BorrowedHeaders()
        slot.get().add("X-Partial", "kept")

        // The content, not the identity: the pool is LIFO, so an accumulator
        // released and taken straight back is the same object with its fields
        // wiped -- which object identity cannot tell from one that was kept.
        assertEquals("kept", slot.get()["X-Partial"]?.toString(), "the fields parsed so far survive")
    }
}
