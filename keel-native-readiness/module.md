# Module keel-native-readiness

The shared implementation of a readiness-based native I/O engine.

Targets: **linuxX64**, **linuxArm64**, **macosArm64**, **macosX64**

## Role

Two of keel's native engines are readiness-based: the kernel says *"this
descriptor can be read or written now"* and the engine then makes the syscall
itself. `keel-engine-epoll` and `keel-engine-kqueue` are that model with
different primitives — `epoll_ctl` / `epoll_wait` against `kevent` — and
everything above the primitive is the same code. It lives here:

| | |
|---|---|
| `AbstractPosixReadinessEventLoop` | the loop: interest ledger, dispatch, shutdown sweep |
| `PosixIoTransport` | a connection: read, write, gather-flush, half-close, teardown |
| `AbstractPosixEngine` | `bind` / `connect`, and the loops' lifecycle |
| `PosixStreamServer`, `PosixPipelinedStreamServer`, `PosixPipelinedChannel` | accepting and handing connections to workers |
| `AbstractPosixEventLoopGroup` | round-robin across loop threads |
| `Interest`, `FdReadyListener`, `LoopParticipant`, `LoopHandoff`, `PosixSuspendRegister`, `PosixEventLoopLifecycle` | the vocabulary those pieces share |

The engines supply the readiness primitive and its cinterop, and nothing else.

## What is not here

**The POSIX socket surface** — `NativeSocket`, `errnoMessage`, `closeFdSafely`
and the rest — is `keel-native-posix`, which this module depends on. Those are
used by every native engine, including the ones that are not readiness-based
(`keel-engine-io-uring` is completion-based; `keel-engine-nwconnection` is
Network.framework), and by `keel-tls-mbedtls`. Keeping them apart is what lets
those three depend on the socket seam without taking a readiness engine with it.

**A completion-based counterpart** does not exist. io_uring's implementation
stands alone today; whether it and a future Windows IOCP engine share enough to
be worth a `keel-native-completion` is a question for when there are two of them
to measure, not before.

## Opt-in

Everything the engines reach for is behind `@InternalReadinessEngineApi`. It is
not an API: it is `internal` that had to cross a Gradle module boundary, which
Kotlin's `internal` does not do.
