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
| `AbstractReadinessEventLoop` | the loop: interest ledger, dispatch, shutdown sweep |
| `ReadinessIoTransport` | a connection: read, write, gather-flush, half-close, teardown |
| `AbstractReadinessEngine` | `bind` / `connect`, and the loops' lifecycle |
| `ReadinessStreamServer`, `ReadinessPipelinedStreamServer`, `ReadinessPipelinedChannel` (internal) | accepting and handing connections to workers |
| `AbstractReadinessEventLoopGroup` | round-robin across loop threads |
| `Interest`, `FdReadyListener`, `LoopParticipant`, `HandoffOutcome`, `Registration`, `ReadinessSuspendRegister`, `ReadinessEventLoopLifecycle` | the vocabulary those pieces share (`LoopHandoff` itself is internal to this module) |

The engines supply the readiness primitive and its cinterop, their `StreamEngine`
entry point, and the loop group that names their loop type.

## What is not here

**The POSIX socket surface** — `NativeSocket`, `errnoMessage`, `closeFdSafely`
and the rest — is `keel-native-posix`, which this module depends on. Those are
used by every native engine, including the ones that are not readiness-based
(`keel-engine-io-uring` is completion-based; `keel-engine-nwconnection` is
Network.framework), and by `keel-tls-mbedtls` and `keel-testing-internal`.
Keeping them apart is what lets those four depend on the socket seam without
taking a readiness engine with it.

**A completion-based counterpart** does not exist. io_uring's implementation
stands alone today; whether it and a future Windows IOCP engine share enough to
be worth a `keel-native-completion` is a question for when there are two of them
to measure, not before.

## Opt-in

Eight types are behind `@InternalReadinessEngineApi`: the loop, its group, the
engine base, the transport, the two servers, the lifecycle the loop implements,
and the registration a waiter holds. Twelve members are too. The engines'
production code reaches exactly two of them — the loop's `cleanupFd` and the
engine's thread resolution. The other ten are used inside this module, or are probes the
engines' seam tests ask about a connection they have just torn down, or both.

The marker is not an API: it is `internal` that had to cross a Gradle module
boundary, which Kotlin's `internal` does not do. It is also weaker than
`internal` — a third party can opt in — and Kotlin offers nothing tighter that
still crosses the boundary.

Five types are public without it. `Interest`, `FdReadyListener`,
`LoopParticipant` and `HandoffOutcome` are the vocabulary the marked types are
declared in terms of, so a caller holding one of those signatures already needs
to name them; putting them behind the marker would add an opt-in without adding
a boundary.

`ReadinessSuspendRegister` is different: it is the type of an engine
constructor's last parameter, and a default parameter's type is part of the
call. Marking it makes plain `KqueueEngine()` — from a benchmark, from a test
helper, from anything — an opt-in site. The marker is for the surface the
engines reach into, not for the signature they are called through.
