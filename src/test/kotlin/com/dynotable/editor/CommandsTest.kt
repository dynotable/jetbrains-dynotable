package com.dynotable.editor

import com.dynotable.files.PartiQLLanguage
import com.dynotable.harness.awaitHighlighting
import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.refusals.VALIDATE_QUERY
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.JsonObject
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.StringValue
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.minutes

/** Server commands, their refusals and the server's requests, against the fake server. */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class CommandsTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  private val commands get() = ServerCommands.getInstance(project.get())

  private suspend fun open(name: String, text: String = "SELECT * FROM t"): VirtualFile =
    withContext(Dispatchers.EDT) { fixture.configureByText(name, text).virtualFile }

  private suspend fun connected() = waitFor("connected, now ${h.core.status}") { h.core.status is Status.Connected }

  private fun args(file: VirtualFile) = listOf(JsonObject().apply { addProperty("uri", file.url) })

  private fun run(test: suspend () -> Unit) = timeoutRunBlocking(2.minutes) { test() }

  // The server sends the sentence as window/showMessage too; the plugin adds nothing. A command that
  // meets a start in flight must wait for it, not say "try again".
  @Test
  fun `Validate during a cold start waits and then answers, with exactly one notification`() = run {
    h.fake.initializeDelayMs = 1500
    h.fake.start()
    val file = open("q.opensearch", "// dynotable: index=orders\n{}")
    val sentence = "OpenSearch parsed this query against \"orders\"."
    assertEquals(sentence, commands.send(file, VALIDATE_QUERY, args(file))?.value)
    waitFor("the server's message") { h.notifications.isNotEmpty() }
    delay(500)
    assertEquals(1, h.notifications.size)
    assertTrue(StringUtil.escapeXmlEntities(sentence) in h.notifications.single().content)
  }

  // Only the connect-time listing latches missing-request.
  @Test
  fun `-32601 on a command shows the server's message with no action and keeps the session`() = run {
    h.fake.handle("command:$VALIDATE_QUERY") { _, _ -> throw ResponseErrorException(ResponseError(-32601, "Unknown command", null)) }
    h.fake.start()
    val file = open("q.opensearch", "{}")
    connected()
    assertNull(commands.send(file, VALIDATE_QUERY, args(file)))
    val shown = h.notifications.single()
    assertEquals("Unknown command", shown.content)
    assertEquals(emptyList<Any>(), shown.actions)
    delay(500)
    assertTrue(h.core.status is Status.Connected)
    assertEquals(1, h.fake.sessions.count { it.initialized })
  }

  // The balloon renders HTML: a hostile server string could load an image from anywhere.
  @Test
  fun `a refusal message and a server showMessage show as plain text`() = run {
    val hostile = """<html><img src="https://example.com/x.png">"""
    h.fake.handle("command:$VALIDATE_QUERY") { _, _ ->
      throw ResponseErrorException(ResponseError(-32803, hostile, JsonObject().apply { addProperty("code", "run-failed") }))
    }
    h.fake.start()
    val file = open("q.opensearch", "{}")
    connected()
    commands.send(file, VALIDATE_QUERY, args(file))
    h.fake.notifyAll("window/showMessage", MessageParams(MessageType.Error, hostile))
    waitFor("two notifications") { h.notifications.size == 2 }
    for (shown in h.notifications) {
      assertFalse("<img" in shown.content, shown.content)
      assertTrue(StringUtil.escapeXmlEntities(hostile) in shown.content, shown.content)
    }
  }

  // The balloon renders HTML, and its action labels render a text that starts with <html> as HTML.
  @Test
  fun `a server showMessageRequest shows its text and its action title as plain text, and answers the server's own title`() = run {
    val hostile = """<html><img src="https://example.com/x.png">"""
    h.fake.start()
    open("q.partiql")
    connected()
    waitFor("one open session") { h.fake.sessions.count { it.initialized } == 1 }
    val params = ShowMessageRequestParams(listOf(MessageActionItem(hostile))).apply {
      type = MessageType.Info
      message = hostile
    }
    val answer = h.fake.sessions.single { it.initialized }.remote.request("window/showMessageRequest", params)
    waitFor("the notification") { h.notifications.isNotEmpty() }
    val shown = h.notifications.single()
    assertFalse("<img" in shown.content, shown.content)
    assertTrue(StringUtil.escapeXmlEntities(hostile) in shown.content, shown.content)
    assertEquals(listOf("\u200B$hostile"), shown.actions.map { it.templateText })
    h.click(shown, "\u200B$hostile")
    assertEquals(MessageActionItem(hostile), withContext(Dispatchers.IO) { answer.get(10, TimeUnit.SECONDS) })
  }

  // The server sends none of these; a listener on a stale port could.
  @Test
  fun `the server's applyEdit, registerCapability, showDocument and configuration are refused`() = run {
    h.fake.start()
    val file = open("squat.partiql", "SELECT 1")
    connected()
    waitFor("didOpen") { h.fake.opened(file.url) }
    val range = Range(Position(0, 0), Position(0, 8))
    val answers = withContext(Dispatchers.IO) {
      h.fake.requestAll("workspace/applyEdit", mapOf("edit" to WorkspaceEdit(mapOf(file.url to listOf(TextEdit(range, "DROP")))))) +
        h.fake.requestAll("client/registerCapability", mapOf("registrations" to listOf(mapOf("id" to "squat", "method" to "workspace/didChangeWatchedFiles")))) +
        h.fake.requestAll("window/showDocument", mapOf("uri" to "https://example.com", "external" to true)) +
        h.fake.requestAll("workspace/configuration", mapOf("items" to listOf(emptyMap<String, Any>())))
    }
    assertEquals(4, answers.size)
    for (answer in answers) {
      val error = (answer as ResponseErrorException).responseError
      assertEquals(-32803 to "The DynoTable client refuses this request.", error.code to error.message)
    }
    assertEquals("SELECT 1", withContext(Dispatchers.EDT) { fixture.editor.document.text })
  }

  // Decision 6: a closed file cannot be open on the server; for an open one the server's own refusal
  // says what is wrong, after a short wait for its diagnostics.
  @Test
  fun `a closed file shows our sentence and sends nothing, and an open file the server never opened gets its refusal`() = run {
    h.fake.ignoreOpens = true
    h.fake.start()
    val closed = open("closed.opensearch", "{}")
    val file = open("q.opensearch", "{}")
    connected()
    withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project.get()).closeFile(closed) }
    assertNull(commands.send(closed, VALIDATE_QUERY, args(closed)))
    assertEquals(listOf(NOT_OPENED), h.sentences())
    assertFalse(h.fake.received.any { it.method == "workspace/executeCommand" })

    assertTrue(measureTimeMillis { assertNull(commands.send(file, VALIDATE_QUERY, args(file))) } >= 3000)
    assertEquals("No open DynoTable document at ${file.url}. Open the file before running it.", h.sentences().last())
    assertEquals(2, h.sentences().size)
  }

  @Test
  fun `a command in an untrusted project shows the untrusted sentence once and starts nothing`() = run {
    val property = "idea.trust.headless.disabled"
    val old = System.setProperty(property, "false")
    try {
      TrustedProjects.setProjectTrusted(project.get(), false)
      val app = h.installApp(h.serving())
      val file = open("q.opensearch", "{}")
      assertNull(commands.send(file, VALIDATE_QUERY, args(file)))
      delay(500)
      assertEquals(listOf(Reason.UNTRUSTED.sentence), h.sentences())
      assertEquals(0, app.starts)
      assertEquals(0, h.fake.initializeParams.size)
    } finally {
      if (old == null) System.clearProperty(property) else System.setProperty(property, old)
    }
  }

  /** A scratch file is outside project content, where the platform hides its own LSP quick fixes. */
  private suspend fun scratch(text: String): VirtualFile = withContext(Dispatchers.EDT) {
    ScratchRootType.getInstance().createScratchFile(project.get(), "fix.partiql", PartiQLLanguage, text)!!.also { fixture.openFileInEditor(it) }
  }

  /**
   * The server offers the [actions] for its URI of [file] for a diagnostic on the first word of the
   * file; answers the quick fixes the editor shows.
   */
  private suspend fun quickFixes(file: VirtualFile, actions: (uri: String) -> List<CodeAction>): List<com.intellij.codeInsight.intention.IntentionAction> {
    connected()
    waitFor("didOpen of the scratch file") { h.fake.openedUri(file.name) != null }
    val uri = h.fake.openedUri(file.name)!!
    h.fake.handle("textDocument/codeAction") { _, _ -> actions(uri) }
    val range = Range(Position(0, 0), Position(0, 5))
    h.fake.notifyAll("textDocument/publishDiagnostics", PublishDiagnosticsParams(uri, listOf(Diagnostic(range, "Unknown keyword"))))
    awaitHighlighting(fixture, "the diagnostic") { infos -> infos.any { it.description == "Unknown keyword" } }
    return withContext(Dispatchers.EDT) { fixture.availableIntentions }
  }

  private fun quickFix(title: String, block: CodeAction.() -> Unit) = CodeAction(title).apply {
    kind = "quickfix"
    block()
  }

  // Decision 4. An edit of another file would apply only in part, so that fix is not offered.
  @Test
  fun `in a scratch file the server's fix shows and applies, and a fix that also edits another file is not offered`() = run {
    h.fake.start()
    val file = scratch("SELEC 1")
    val first = Range(Position(0, 0), Position(0, 5))
    val intentions = quickFixes(file) { uri ->
      listOf(
        quickFix("Replace with SELECT") { edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(first, "SELECT")))) },
        quickFix("Edit two files") { edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(first, "X")), "file:///other.partiql" to listOf(TextEdit(first, "X")))) },
      )
    }
    val titles = intentions.map { it.text }
    assertFalse("Edit two files" in titles, "$titles")
    withContext(Dispatchers.EDT) { fixture.launchAction(intentions.single { it.text == "Replace with SELECT" }) }
    assertEquals("SELECT 1", withContext(Dispatchers.EDT) { fixture.editor.document.text })
  }

  // Decision 4: a versioned edit applies only to the version the server saw; a file operation or a snippet cannot apply here.
  @Test
  fun `a versioned fix of the current version applies, and a stale version, a file operation or a snippet is not offered`() = run {
    h.fake.start()
    val file = scratch("SELEC 1")
    val first = Range(Position(0, 0), Position(0, 5))
    fun versioned(uri: String, version: Int, edit: Either<TextEdit, SnippetTextEdit>) =
      WorkspaceEdit(listOf(Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(uri, version), listOf(edit)))))
    val intentions = quickFixes(file) { uri ->
      listOf(
        quickFix("Current version") { edit = versioned(uri, 0, Either.forLeft(TextEdit(first, "SELECT"))) },
        quickFix("Stale version") { edit = versioned(uri, 5, Either.forLeft(TextEdit(first, "X"))) },
        quickFix("Snippet") { edit = versioned(uri, 0, Either.forRight(SnippetTextEdit(first, StringValue().apply { kind = "snippet"; value = "SELECT \$1" }))) },
        quickFix("Create file") { edit = WorkspaceEdit(listOf(Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile("file:///new.partiql")))) },
      )
    }
    val titles = intentions.map { it.text }
    for (title in listOf("Stale version", "Snippet", "Create file")) assertFalse(title in titles, "$titles")
    withContext(Dispatchers.EDT) { fixture.launchAction(intentions.single { it.text == "Current version" }) }
    assertEquals("SELECT 1", withContext(Dispatchers.EDT) { fixture.editor.document.text })
  }

  // A server may send one edit in both forms; applying both would insert the text twice.
  @Test
  fun `a fix that sends its edit as both changes and documentChanges applies it once`() = run {
    h.fake.start()
    val file = scratch("SELEC 1")
    val first = Range(Position(0, 0), Position(0, 5))
    val intentions = quickFixes(file) { uri ->
      listOf(
        quickFix("Both forms") {
          edit = WorkspaceEdit(listOf(Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(VersionedTextDocumentIdentifier(uri, 0), listOf(Either.forLeft(TextEdit(first, "SELECT")))))))
            .apply { changes = mapOf(uri to listOf(TextEdit(first, "SELECT"))) }
        },
      )
    }
    withContext(Dispatchers.EDT) { fixture.launchAction(intentions.single { it.text == "Both forms" }) }
    assertEquals("SELECT 1", withContext(Dispatchers.EDT) { fixture.editor.document.text })
  }

  @Test
  fun `a profile-required refusal of a quick fix's command shows its sentence with Select Profile`() = run {
    val refusal = h.fake.fixture("error-profile-required.json").asJsonObject
    h.fake.handle("command:$VALIDATE_QUERY") { _, _ -> throw h.fake.refusal("error-profile-required.json") }
    h.fake.start()
    val file = scratch("SELEC 1")
    val intentions = quickFixes(file) { uri -> listOf(quickFix("Validate") { command = Command("Validate", VALIDATE_QUERY, listOf(JsonObject().apply { addProperty("uri", uri) })) }) }
    withContext(Dispatchers.EDT) { fixture.launchAction(intentions.single { it.text == "Validate" }) }
    waitFor("the refusal") { h.notifications.isNotEmpty() }
    val shown = h.notifications.single()
    assertEquals(StringUtil.escapeXmlEntities(refusal["message"].asString), shown.content)
    assertEquals(listOf("Select Profile"), shown.actions.map { it.templateText })
  }
}
