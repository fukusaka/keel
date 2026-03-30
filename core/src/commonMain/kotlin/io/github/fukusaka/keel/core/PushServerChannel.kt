package io.github.fukusaka.keel.core

/**
 * Marker interface for a [ServerChannel] whose accepted connections
 * implement [PushChannel] (push-model read).
 *
 * A class implementing [PushServerChannel] MUST also implement [ServerChannel].
 * The [ServerChannel.accept] method returns [Channel] instances that also
 * implement [PushChannel], enabling callers to use
 * [PushChannel.asPushSuspendSource] for zero-copy reading.
 *
 * @see ServerChannel for the base server channel interface
 * @see PushChannel for the push-model channel marker
 */
interface PushServerChannel
