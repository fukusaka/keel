# Module keel-core

Core I/O engine interfaces, channel pipeline framework, and logging facade.

# Package io.github.fukusaka.keel.core

IoEngine, Channel, ServerChannel, IoEngineConfig, SocketAddress — the public API for binding servers and creating connections.

# Package io.github.fukusaka.keel.pipeline

ChannelPipeline framework for zero-suspend protocol processing. Includes DefaultChannelPipeline, IoTransport, PipelinedChannel, and handler interfaces (ChannelInboundHandler, ChannelOutboundHandler, TypedChannelInboundHandler).

# Package io.github.fukusaka.keel.logging

Logger, LoggerFactory, LogLevel, and default implementations (NoopLoggerFactory, PrintLogger).
