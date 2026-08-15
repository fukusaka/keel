package io.github.fukusaka.keel.testing.buf

import io.github.fukusaka.keel.buf.IoBuf

/**
 * An [IoBuf] that does not implement the native-pointer interface, so the
 * cast behind `IoBuf.unsafePointer` fails on it with a `ClassCastException`.
 *
 * The inverse of [FailingReleaseIoBuf], whose delegation deliberately adds
 * the pointer back so a flush can reach the release it stands in for. This
 * one stands in for an allocator whose buffers are not native, at the seam
 * where a transport first touches the pointer — everything else, including
 * [release][IoBuf.release], delegates.
 */
public class PointerlessIoBuf(delegate: IoBuf) : IoBuf by delegate
