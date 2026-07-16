# Module keel-testing-server-http

In-process test harness for `keel-server-http` — configure a real
`keelHttpServer` with the production builder DSL and drive it through an
HTTP test client, with `keel-testing-engine`'s `InMemoryEngine` standing in
for the OS socket layer.

## Usage

```kotlin
@Test fun usersRoute() = runTest {
    keelHttpTest {
        server {
            get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
        }
        val res = client.get("/users/42")
        assertEquals(HttpStatus.OK, res.status)
        assertEquals("user 42", res.bodyText())
    }
}
```

`keelHttpTest { }` creates the engine and a `KeelHttpTestScope`; on exit
(return or throw) the server is stopped and the engine closed — lifecycle
is fully automatic. `server { }` records the configuration with the same
`KeelHttpServerBuilder` API used in production (`get` / `post` / `install`
/ `notFound` / `exception` / ...); the server starts lazily on the client's
first request.

## Key types

| Type | Role |
|------|------|
| `keelHttpTest` | Entry point — runs a block against an in-process server, with automatic teardown |
| `KeelHttpTestScope` | Block receiver: `server { }` configuration plus the `client` |
| `KeelHttpTestClient` | HTTP/1.1 test client: `request(...)` plus `get` / `post` / `put` / `delete` / `head` / `options` / `patch` shorthands; one fresh in-memory connection per request |
| `TestHttpResponse` | Parsed response: `status`, `headers`, `bodyBytes()` / `bodyText()` |

The client encodes each request to HTTP/1.1 wire bytes, reads the raw
response off the loopback channel (stopping as soon as the
`Content-Length` / chunked framing says the response is complete, so
keep-alive connections do not stall the test), and parses it with a
minimal hand-rolled response decoder. This is an interim client-side
implementation — `keel-codec-http` ships only the server-side codec today —
to be replaced when a keel client-side codec exists.

# Package io.github.fukusaka.keel.testing.server.http

`keelHttpTest`, `KeelHttpTestScope`, `KeelHttpTestClient`,
`TestHttpResponse`.
