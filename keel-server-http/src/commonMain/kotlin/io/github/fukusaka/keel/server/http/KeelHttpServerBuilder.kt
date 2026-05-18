package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.server.KeelServerDsl
import io.github.fukusaka.keel.server.ServerConnector
import io.github.fukusaka.keel.server.ServerConnectorBuilder
import kotlin.reflect.KClass
import io.github.fukusaka.keel.server.connector as buildConnector

/**
 * Configuration builder for [keelHttpServer].
 *
 * Configure the listening endpoint (bind address, port, transport
 * options, TLS) with [connector], and register routes with [route] or
 * the method-specific shorthands ([get], [post], [put], [delete],
 * [patch], [head], [options]). Each registers a [RouteHandler] against a
 * path pattern in the server's [Router] (see [Router] for the pattern
 * syntax). Middleware is added with [install], protocol-upgrade
 * endpoints (such as WebSocket) with [upgrade], and error handling — a
 * custom `404` via [notFound], exception-to-response mapping via
 * [exception].
 */
@KeelServerDsl
public class KeelHttpServerBuilder internal constructor() {

    private var connector: ServerConnector? = null
    private val router = Router()
    private val middlewares = mutableListOf<Middleware>()
    private var notFoundHandler: RouteHandler? = null
    private val exceptionMappers = mutableListOf<ExceptionMapper>()

    /**
     * Configures the listening endpoint via the protocol-neutral
     * `connector { }` DSL of `:keel-server` — bind address, port,
     * transport options, and TLS (see [ServerConnector]).
     *
     * When omitted, the server binds an OS-assigned ephemeral port on
     * all interfaces. May be called at most once. The bind host must be
     * an IP literal — the Pipeline-mode bind cannot resolve hostnames.
     *
     * @throws IllegalStateException if a connector is already configured.
     */
    public fun connector(configure: ServerConnectorBuilder.() -> Unit) {
        check(connector == null) { "connector is already configured" }
        connector = buildConnector(configure)
    }

    /** Registers [handler] for [method] requests matching the [path] pattern. */
    public fun route(method: HttpMethod, path: String, handler: RouteHandler) {
        router.register(method, path, handler)
    }

    /** Registers a `GET` route. */
    public fun get(path: String, handler: RouteHandler): Unit = route(HttpMethod.GET, path, handler)

    /** Registers a `POST` route. */
    public fun post(path: String, handler: RouteHandler): Unit = route(HttpMethod.POST, path, handler)

    /** Registers a `PUT` route. */
    public fun put(path: String, handler: RouteHandler): Unit = route(HttpMethod.PUT, path, handler)

    /** Registers a `DELETE` route. */
    public fun delete(path: String, handler: RouteHandler): Unit = route(HttpMethod.DELETE, path, handler)

    /** Registers a `PATCH` route. */
    public fun patch(path: String, handler: RouteHandler): Unit = route(HttpMethod.PATCH, path, handler)

    /** Registers a `HEAD` route. */
    public fun head(path: String, handler: RouteHandler): Unit = route(HttpMethod.HEAD, path, handler)

    /** Registers an `OPTIONS` route. */
    public fun options(path: String, handler: RouteHandler): Unit = route(HttpMethod.OPTIONS, path, handler)

    /**
     * Opens a route group at [prefix] (see [RouteGroupBuilder]).
     *
     * Routes registered inside [configure] are prefixed with [prefix] and
     * wrapped with any middleware the group `install`s; nested `route`
     * blocks compose prefixes and middleware further. The group is pure
     * registration sugar — once [configure] has run it registers ordinary
     * routes on the [Router], so the trie and the resolver are unchanged.
     */
    public fun route(prefix: String, configure: RouteGroupBuilder.() -> Unit) {
        RouteGroupBuilder(prefix).apply(configure).flush(
            inheritedMiddleware = emptyList(),
            inheritedPrefix = "",
            register = { method, path, handler -> route(method, path, handler) },
        )
    }

    /**
     * Mounts [source] as a static-asset tree at [urlPath].
     *
     * Registers a `GET` and a `HEAD` route at `urlPath` plus a trailing
     * `*` wildcard, so a request for `urlPath/foo/bar.css` resolves the
     * asset `foo/bar.css` from [source] (see [StaticAssetHandler] for the
     * serve semantics — `Content-Type`, conditional GET, `404`).
     *
     * This is the core static-serving primitive; [staticFiles] and
     * [staticFile] are sugar over it. A caller-supplied [AssetSource]
     * already carries its own content-type / ETag behaviour, so there is
     * no per-mount configuration block here — use [staticFiles] when you
     * want the [ContentTypeResolver] / [ETagGenerator] knobs. The path
     * safety of a custom [source] is the implementer's responsibility.
     */
    public fun staticAssets(urlPath: String, source: AssetSource) {
        val handler = StaticAssetHandler(source)
        val wildcardPath = if (urlPath.endsWith("/")) "$urlPath*" else "$urlPath/*"
        router.register(HttpMethod.GET, wildcardPath) { call -> handler.handle(call) }
        router.register(HttpMethod.HEAD, wildcardPath) { call -> handler.handle(call) }
    }

    /**
     * Serves the files under [directory] at [urlPath].
     *
     * Sugar for [staticAssets] with a [FilesystemAssetSource] over the
     * default [kotlinx.io.files.SystemFileSystem]; the full 5-layer
     * path-traversal defense applies. The [configure] block's
     * [StaticFilesBuilder.mimeTypes] / [StaticFilesBuilder.etag] are
     * applied to the filesystem source.
     */
    public fun staticFiles(
        urlPath: String,
        directory: String,
        configure: StaticFilesBuilder.() -> Unit = {},
    ) {
        val options = StaticFilesBuilder().apply(configure)
        val source = FilesystemAssetSource(
            root = directory,
            contentTypeResolver = options.mimeTypes,
            etagGenerator = options.etag,
        )
        staticAssets(urlPath, source)
    }

    /**
     * Serves the single file at [filePath] at the exact route [urlPath].
     *
     * Registers an exact `GET` + `HEAD` route — no wildcard, no
     * traversal defense, since the served path is fixed by the server
     * rather than derived from the request.
     */
    public fun staticFile(
        urlPath: String,
        filePath: String,
        configure: StaticFilesBuilder.() -> Unit = {},
    ) {
        val options = StaticFilesBuilder().apply(configure)
        val source = FilesystemAssetSource(
            root = filePath,
            contentTypeResolver = options.mimeTypes,
            etagGenerator = options.etag,
        )
        val handler = StaticAssetHandler(source)
        router.register(HttpMethod.GET, urlPath) { call -> handler.handle(call) }
        router.register(HttpMethod.HEAD, urlPath) { call -> handler.handle(call) }
    }

    /**
     * Installs [middleware] as a stage of the request chain. Middleware
     * runs in installation order, the first installed being the
     * outermost, and wraps the dispatch of every request (see
     * [Middleware]).
     */
    public fun install(middleware: Middleware) {
        middlewares.add(middleware)
    }

    /**
     * Registers [protocol] as the upgrade endpoint for the [path] pattern.
     * A request to [path] whose `Upgrade` header token names the protocol
     * is handed to it instead of a route handler (see [UpgradeProtocol]).
     *
     * The [path] pattern shares [Router] syntax — `:name` parameters and a
     * trailing `*` work. Higher-level DSLs, such as the `webSockets { }`
     * block in `keel-server-websocket`, build on this.
     */
    public fun upgrade(path: String, protocol: UpgradeProtocol) {
        router.registerUpgrade(path, protocol)
    }

    /**
     * Sets the handler invoked when no route matches, replacing the
     * built-in `404 Not Found`. The handler runs as the terminal of the
     * middleware chain, so middleware still observes the request.
     *
     * @throws IllegalStateException if a `notFound` handler is already set.
     */
    public fun notFound(handler: RouteHandler) {
        check(notFoundHandler == null) { "notFound handler is already registered" }
        notFoundHandler = handler
    }

    /**
     * Registers [handler] to turn a thrown [T] — escaping the route
     * handler and the middleware chain — into a response, replacing the
     * built-in `500` for that exception type.
     *
     * Mappers are consulted in registration order, the first whose type
     * matches the thrown exception winning; register more specific
     * exception types first. An exception matching no mapper falls back
     * to the built-in `500`.
     */
    public inline fun <reified T : Throwable> exception(
        noinline handler: suspend (call: HttpCall, cause: T) -> Unit,
    ) {
        addExceptionMapper(T::class) { call, cause -> handler(call, cause as T) }
    }

    /** Backs the inline [exception]; not part of the public API. */
    @PublishedApi
    internal fun addExceptionMapper(
        type: KClass<out Throwable>,
        handler: suspend (HttpCall, Throwable) -> Unit,
    ) {
        exceptionMappers.add(ExceptionMapper(type, handler))
    }

    internal fun build(engine: StreamEngine): KeelHttpServer =
        KeelHttpServer(
            engine,
            connector ?: ServerConnector(),
            router,
            middlewares.toList(),
            ErrorHandlers(notFoundHandler, exceptionMappers.toList()),
        )
}

/**
 * Builds a [KeelHttpServer] on [engine].
 *
 * The returned server is not yet bound — call [KeelHttpServer.start] to
 * begin accepting connections.
 *
 * ```
 * val server = keelHttpServer(engine) {
 *     connector { port = 8080 }
 *     install { call, next -> next() }
 *     get("/hello") { call -> call.respond(HttpResponse.ok("Hello")) }
 *     get("/users/:id") { call -> call.respond(HttpResponse.ok(call.pathParameters["id"])) }
 * }
 * server.start()
 * ```
 */
public fun keelHttpServer(
    engine: StreamEngine,
    configure: KeelHttpServerBuilder.() -> Unit,
): KeelHttpServer = KeelHttpServerBuilder().apply(configure).build(engine)
