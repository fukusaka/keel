package io.github.fukusaka.keel.pipeline

/**
 * Thrown when a pipeline type chain validation fails at construction time.
 *
 * This indicates that adjacent handlers have incompatible message types:
 * the previous handler's [ChannelInboundHandler.producedType] is not
 * assignable to the next handler's [ChannelInboundHandler.acceptedType].
 */
class PipelineTypeException(message: String) : IllegalArgumentException(message)
