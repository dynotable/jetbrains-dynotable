package com.dynotable.fake

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/** One client connection. */
class Session internal constructor(val socket: Socket) {
  @Volatile
  var initialized = false
    internal set

  /** Sends to this client. */
  lateinit var remote: RemoteEndpoint
    internal set
}

/** Answers a request with a JSON-encodable value, or throws [ResponseErrorException]. */
typealias Handler = (params: JsonElement, session: Session) -> Any?

/** A message from an initialized session. [id] is set for a request. */
data class Message(val method: String, val params: JsonElement, val id: String? = null)

/**
 * A DynoTable language server on loopback that answers from the `contract/` fixtures. Tests replace
 * an answer with [handle]. [start] writes the port file, [close] deletes it.
 */
class FakeServer(
  private val portFile: Path,
  private val contractDir: Path = Path.of(System.getProperty("dynotable.projectDir"), "contract"),
) : AutoCloseable {
  @Volatile
  var token: String = newToken()

  var port = 0
    private set

  /** The `serverInfo.version` that `initialize` answers. */
  @Volatile
  var version: String = fixture("initialize.json").asJsonObject["serverInfo"].asJsonObject["version"].asString

  /** How long `initialize` waits before it answers. */
  @Volatile
  var initializeDelayMs = 0L

  /** Runs first in each `initialize`; it may throw [ResponseErrorException] to refuse it. */
  @Volatile
  var onInitialize: (() -> Unit)? = null

  /** Runs after each accepted `initialize`, before the answer. */
  @Volatile
  var afterInitialize: (() -> Unit)? = null

  /** The params of every `initialize`, also a refused one. */
  val initializeParams = CopyOnWriteArrayList<JsonElement>()

  /** Every message from an initialized session, in arrival order. */
  val received = CopyOnWriteArrayList<Message>()

  val sessions: MutableSet<Session> = ConcurrentHashMap.newKeySet()

  private val handlers = ConcurrentHashMap<String, Handler>()
  private val languages = ConcurrentHashMap<String, String>()
  private val gson = MessageJsonHandler(emptyMap()).gson
  private val threads = AtomicInteger()
  private val executor = Executors.newCachedThreadPool { task ->
    Thread(task, "FakeServer-${threads.incrementAndGet()}").apply { isDaemon = true }
  }
  private var server: ServerSocket? = null

  fun fixture(file: String): JsonElement = JsonParser.parseString(Files.readString(contractDir.resolve(file)))

  /** Answers [method] (or `command:<id>` for `workspace/executeCommand`) with [handler]. */
  fun handle(method: String, handler: Handler) {
    handlers[method] = handler
  }

  /** Restores the fixture answer of [method]. */
  fun unhandle(method: String) {
    handlers.remove(method)
  }

  /** Sends [method] to every initialized session. */
  fun notifyAll(method: String, params: Any) = sessions.filter { it.initialized }.forEach { it.remote.notify(method, params) }

  /** The sessions that sent `initialized`: platform sessions. The preflight never sends it. */
  fun platformSessions(): Int = received.count { it.method == "initialized" }

  /** The `languageId` of the `didOpen` for [uri]. */
  fun language(uri: String): String? = languages[uri]

  /** The refusal of the error fixture [file], to throw from a handler. */
  fun refusal(file: String) = ResponseErrorException(gson.fromJson(fixture(file), ResponseError::class.java))

  /** True once a `textDocument/didOpen` for [uri] arrived. */
  fun opened(uri: String): Boolean = languages.containsKey(uri)

  fun start() {
    val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    server = socket
    port = socket.localPort
    executor.execute {
      while (!socket.isClosed) {
        val client = try {
          socket.accept()
        } catch (_: IOException) {
          break
        }
        accept(client)
      }
    }
    writePortFile()
  }

  /** Drops every session and stops serving, as an app that quits does. */
  override fun close() {
    portFile.deleteIfExists()
    server?.close()
    server = null
    sessions.forEach { it.socket.close() }
    executor.shutdownNow()
    // A thread that outlives the test fails the platform's thread leak check.
    check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "the fake server threads did not end" }
  }

  private fun writePortFile() {
    portFile.parent.createDirectories()
    val temp = portFile.resolveSibling("${portFile.fileName}.tmp")
    temp.writeText("""{"port":$port,"token":"$token","pid":${ProcessHandle.current().pid()}}""")
    runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
    Files.move(temp, portFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun accept(socket: Socket) {
    val session = Session(socket)
    sessions += session
    val launcher = Launcher.Builder<LanguageClient>()
      .setLocalService(endpoint(session))
      .setRemoteInterface(LanguageClient::class.java)
      .setInput(socket.getInputStream())
      .setOutput(socket.getOutputStream())
      .setExecutorService(executor)
      // The wrapper applies to both directions; only the consumer of incoming messages is the endpoint.
      .wrapMessages { next -> if (next is RemoteEndpoint) recording(session, next) else next }
      .create()
    session.remote = launcher.remoteEndpoint
    executor.execute {
      try {
        launcher.startListening().get()
      } catch (_: Exception) {
        // The socket closed.
      } finally {
        sessions -= session
        socket.close()
      }
    }
  }

  // Here, before the endpoint, so a `$/cancelRequest` (which lsp4j consumes itself) is recorded too.
  private fun recording(session: Session, next: MessageConsumer) = MessageConsumer { message ->
    if (session.initialized) {
      when (message) {
        is RequestMessage -> received += Message(message.method, json(message.params), message.id)
        is NotificationMessage -> received += Message(message.method, json(message.params))
      }
    }
    next.consume(message)
  }

  private fun json(params: Any?): JsonElement = params as? JsonElement ?: gson.toJsonTree(params) ?: JsonNull.INSTANCE

  private fun endpoint(session: Session) = object : Endpoint {
    override fun request(method: String, parameter: Any?): CompletableFuture<*> = try {
      CompletableFuture.completedFuture(request(session, method, json(parameter)))
    } catch (e: ResponseErrorException) {
      CompletableFuture.failedFuture<Any?>(e)
    }

    override fun notify(method: String, parameter: Any?) = notification(session, method, json(parameter))
  }

  private fun request(session: Session, method: String, params: JsonElement): Any? {
    if (method == "initialize") return initialize(session, params)
    if (!session.initialized) refuse(session)
    val command = if (method == "workspace/executeCommand") params.asJsonObject else null
    handlers[if (command != null) "command:${command["command"].asString}" else method]?.let { return it(params, session) }
    return when {
      method == "shutdown" -> null
      method == "dynotable/listProfiles" -> fixture("list-profiles.json")
      method == "dynotable/listTables" -> fixture("list-tables.json")
      method == "dynotable/listIndices" -> fixture("list-indices.json")
      command != null -> command(session, command)
      // A capability the fake does not model answers empty rather than failing the client.
      method.startsWith("textDocument/") -> null
      else -> fail(ResponseErrorCode.MethodNotFound.value, "Unhandled method $method")
    }
  }

  private fun initialize(session: Session, params: JsonElement): JsonElement {
    initializeParams += params
    Thread.sleep(initializeDelayMs)
    onInitialize?.invoke()
    if (session.initialized) fail(ResponseErrorCode.RequestFailed.value, "This connection is already initialized.", runFailed())
    val options = params.asJsonObject["initializationOptions"]?.takeIf { it.isJsonObject }?.asJsonObject
    if (options?.get("token")?.takeIf { it.isJsonPrimitive }?.asString != token) refuse(session)
    session.initialized = true
    afterInitialize?.invoke()
    return fixture("initialize.json").asJsonObject.apply { getAsJsonObject("serverInfo").addProperty("version", version) }
  }

  private fun command(session: Session, params: JsonObject): Any? {
    val uri = params["arguments"]?.asJsonArray?.firstOrNull()?.asJsonObject?.get("uri")?.asString
    val dialect = languages[uri].takeIf { it == "partiql" || it == "opensearch" } ?: "sql"
    return when (val command = params["command"].asString) {
      "dynotable.runStatement" -> fixture("run-$dialect.json")
      "dynotable.openInApp" -> null
      "dynotable.validateQuery" -> {
        session.remote.notify("window/showMessage", fixture("validate-query-message.json"))
        fixture("validate-query.json")
      }
      else -> fail(ResponseErrorCode.MethodNotFound.value, "Unknown command $command")
    }
  }

  private fun notification(session: Session, method: String, params: JsonElement) {
    // The server drops a notification that arrives before `initialize`.
    if (!session.initialized) return
    when (method) {
      "exit" -> session.socket.close()
      "textDocument/didOpen" -> params.asJsonObject["textDocument"].asJsonObject.let {
        languages[it["uri"].asString] = it["languageId"].asString
        // As the app: diagnostics for every opened document, also a clean one.
        session.remote.notify("textDocument/publishDiagnostics", JsonObject().apply {
          add("uri", it["uri"])
          add("diagnostics", JsonArray())
        })
      }
    }
  }

  /** As the app: the refused socket closes [AUTH_DROP_MS] after the answer. */
  private fun refuse(session: Session): Nothing {
    executor.execute {
      try {
        Thread.sleep(AUTH_DROP_MS)
      } catch (_: InterruptedException) {
        // close() interrupts; the socket closes now.
      }
      session.socket.close()
    }
    throw ResponseErrorException(gson.fromJson(fixture("error-auth.json"), ResponseError::class.java))
  }

  private fun fail(code: Int, message: String, data: Any? = null): Nothing = throw ResponseErrorException(ResponseError(code, message, data))

  private fun runFailed() = JsonObject().apply { addProperty("code", "run-failed") }

  private companion object {
    const val AUTH_DROP_MS = 250L
    val random = SecureRandom()

    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
  }
}
