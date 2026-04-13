package io.github.fukusaka.keel.pipeline

/**
 * Thrown when a pipeline type chain validation fails at construction time.
 *
 * This indicates that adjacent handlers have incompatible message types:
 * the previous handler's [InboundHandler.producedType] is not
 * assignable to the next handler's [InboundHandler.acceptedType].
 */
class PipelineTypeException(message: String) : IllegalArgumentException(message)
