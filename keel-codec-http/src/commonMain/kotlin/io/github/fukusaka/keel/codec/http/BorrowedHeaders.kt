package io.github.fukusaka.keel.codec.http

/**
 * A decoder's borrow of a pooled [HttpHeaders], and the record of whether it
 * still holds one.
 *
 * The decoders parse into an accumulator borrowed from [HttpHeadersPool] and
 * hand it to each message they emit, borrowing again when the next one writes
 * a header. That much is parse state. What is not is the question this type
 * answers:
 * **do we still own the instance the field points at?**
 *
 * It has to be asked because a connection's ending can arrive in the middle of
 * a parse. A handler closing the channel from a request head — which is what a
 * server's shutdown drain does — raises `onInactive` from inside the decoder's
 * own downstream dispatch, and the accumulator goes back to the pool while the
 * parse frame is still running. Whatever is written after that lands in an
 * instance the pool has already promised to someone else: measured, three
 * pipelined requests delivered the second one's header values on the third,
 * and with another connection borrowing in between, that connection's request
 * carried a header this one had parsed.
 *
 * The record is kept here rather than asked of the instance. `HttpHeaders`
 * can say whether it is idle in the pool, but that answers "no" once another
 * borrower has taken it — exactly when writing into it is worst. Ownership is
 * the borrower's fact, so the borrower keeps it.
 *
 * Nothing hands out an instance this type does not own: [get] takes a fresh
 * borrow instead. That is why the guard cannot be in the wrong place — there
 * is no other way to reach the accumulator.
 *
 * **Lifetime.** A borrow is taken the first time the accumulator is used -- a
 * read as much as a write, and [transfer] counts, so a message with no header
 * fields takes one too. It is handed to the message it filled when that is
 * emitted ([transfer]), and returned when a parse is abandoned or the
 * connection ends ([recycle]). Nothing is held between messages. A connection
 * that ends part-way through a message gives that accumulator back too --
 * [recycle] does not ask whether one is in flight -- and after the ending the
 * decoder decodes nothing, so [get] is not called again: once the emitted
 * heads are released the pool is as it was. Measured, a primed pool is the
 * same size after a run of connections that end and then read on.
 *
 * That the decoder stops at its ending is what closes the accounting. A parse
 * begun after it would hold a pooled instance and the recv buffer the
 * accumulator's range entries retain, with no second ending to give either
 * back; measured before the decoders ended, twenty such connections held
 * twenty buffers.
 *
 * **Not generic, deliberately.** The hazard is not specific to these decoders
 * — a handler that releases something on `onInactive` and keeps receiving has
 * the same problem — but the remedy splits in two, and only one half needs a
 * type. A handler that can be *without* the resource releases it and puts the
 * field beyond reach: [HttpBodyAggregator] and [HttpResponseBodyAggregator]
 * both release a held head and chunks and then null them. Only a decoder needs
 * an accumulator at all times, borrowed afresh per message, and it is the only
 * such holder in the tree. Making this generic would mean abstracting over two
 * unlike borrow protocols — this pool's scope-local stack and caller-cache
 * handle, and `IoBuf`'s allocator and reference count — with one consumer
 * each. Worth doing when a second per-message borrow appears; not before.
 *
 * **Threading**: not thread-safe. Owned by one decoder, which the pipeline
 * confines to its channel's event loop.
 */
internal class BorrowedHeaders {

    /**
     * The pool stack for the scope this borrow lives on, resolved on the first
     * borrow rather than at construction — construction can run off the event
     * loop, and [HttpHeadersPool.borrowFrom] records the stack it is given as
     * the caller-cache handle, so resolving it there would capture the wrong
     * one. No test pins the difference: resolving at construction passes the
     * whole module, because nothing in it constructs a decoder off the loop.
     */
    private var stack: ArrayDeque<HttpHeaders>? = null

    /** The instance currently held, or `null` when the slot holds none. */
    private var held: HttpHeaders? = null

    /**
     * The accumulator to parse into, borrowing one if the slot holds none.
     *
     * Every read and write of the accumulator goes through here, so a released
     * instance is never written into and never handed downstream.
     */
    fun get(): HttpHeaders = held ?: borrow().also { held = it }

    /**
     * Gives the accumulator to the message being emitted and gives up
     * ownership; the next [get] borrows a fresh one.
     */
    fun transfer(): HttpHeaders = get().also { held = null }

    /**
     * Returns the accumulator to the pool. Used where a parse is abandoned and
     * where the connection ends: in both the instance never reached a message,
     * so this slot still owns it.
     *
     * The ending is not a special operation. It was tried as one — taking a
     * replacement there, or remembering the ending and giving back what later
     * reads borrow — and both were worse: the first held a borrow nothing gave
     * back, and the second discarded a header block that straddled a read
     * boundary, so a `Content-Length` was lost and the body bytes were
     * delivered as a request of their own. Neither is needed for the ownership
     * this type keeps: a borrow is taken on first use and handed to the
     * message at [transfer], and the decoder decodes nothing after its
     * ending, so no read after it borrows at all. That the ending discards an
     * in-flight header block is the decoder's contract, not this type's:
     * [recycle] gives back whatever it holds and does not ask.
     */
    fun recycle() {
        held?.release()
        held = null
    }

    private fun borrow(): HttpHeaders {
        val resolved = stack ?: headersPoolStack().also { stack = it }
        return HttpHeadersPool.borrowFrom(resolved)
    }
}
