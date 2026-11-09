package com.dynotable.live

import com.dynotable.connect.DynoTableClientDescriptor
import com.dynotable.connect.MIN_APP_VERSION
import com.dynotable.connect.meetsMinimum
import com.dynotable.editor.PLAN_PROVIDER_ID
import com.dynotable.editor.ServerCommands
import com.dynotable.explorer.Explorer
import com.dynotable.explorer.Node
import com.dynotable.harness.awaitHighlighting
import com.dynotable.harness.lensRow
import com.dynotable.harness.serverLenses
import com.dynotable.harness.waitFor
import com.dynotable.query.IndexPicker
import com.dynotable.query.NewQuery
import com.dynotable.results.Results
import com.dynotable.results.View
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.profiles.RequestedProfile
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.minutes

/** Seeded tables: `orders` has `customerId`, `status` and `orderId`. */
private const val ORDERS = "orders"
private const val CUSTOMERS = "customers"

/** A buffer, and where to ask in it. */
private class Cell(val text: String, val line: Int = 0, val character: Int = 0) {
  /** The text with the fixture's caret marker at the position. */
  fun withCaret(): String {
    val lines = text.split("\n").toMutableList()
    lines[line] = lines[line].substring(0, character) + "<caret>" + lines[line].substring(character)
    return lines.joinToString("\n")
  }
}

/** A dialect's buffers: one with a mistake that has a quick fix, and per capability a buffer that gets an answer, or `null` where the spec gives none. */
private class Cells(val suffix: String, val diagnostics: Cell, val probes: Map<String, Cell?>)

/**
 * Formatting is not offered for PartiQL. Definition, highlight and rename read SQL table aliases
 * only. An OpenSearch query gets no lens and no plan.
 */
private fun cellsOf(dialect: String, index: String): Cells {
  val alias = Cell("select o.orderId from \"$ORDERS\" o where o.status = 'x'", 0, 7)
  val where = Cell("SELECT * FROM \"$ORDERS\" WHERE ", 0, 29)
  val commaJoin = Cell("SELECT * FROM \"$ORDERS\" o, \"$CUSTOMERS\" c")
  val like = Cell("SELECT * FROM \"$ORDERS\" WHERE \"status\" LIKE 'a%'")
  val table = Cell("SELECT * FROM \"$ORDERS\"", 0, 15)
  val misspelt = Cell("// dynotable: index=$index\n{\"query\":{\"mtach\":{\"status\":\"x\"}}}")
  val match = "// dynotable: index=$index\n{\"query\":{\"match\":{\"status\":\"x\"}}}"
  return when (dialect) {
    "sql" -> Cells(
      ".dynotable.sql",
      commaJoin,
      mapOf(
        "completionProvider" to where, "codeActionProvider" to commaJoin, "codeLensProvider" to alias, "hoverProvider" to alias,
        "inlayHintProvider" to alias, "documentFormattingProvider" to alias, "definitionProvider" to alias,
        "documentHighlightProvider" to alias, "renameProvider" to alias,
      ),
    )
    "partiql" -> Cells(
      ".partiql",
      like,
      mapOf(
        "completionProvider" to where, "codeActionProvider" to like, "codeLensProvider" to table, "hoverProvider" to table,
        "inlayHintProvider" to table, "documentFormattingProvider" to null, "definitionProvider" to null,
        "documentHighlightProvider" to null, "renameProvider" to null,
      ),
    )
    // Inside the field name, the index mapping's fields; on the clause name, its hover.
    else -> Cells(
      ".opensearch",
      misspelt,
      mapOf(
        "completionProvider" to Cell(match, 1, 22), "codeActionProvider" to misspelt, "codeLensProvider" to null,
        "hoverProvider" to Cell(match, 1, 12), "inlayHintProvider" to null, "documentFormattingProvider" to Cell(match),
        "definitionProvider" to null, "documentHighlightProvider" to null, "renameProvider" to null,
      ),
    )
  }
}

/** The plugin against the real app, through the wrapper (Testing Strategy › Live). Each test opens a new project, so nothing has chosen a profile yet. */
@TestApplication
@Tag("live")
@EnabledOnOs(OS.MAC)
class LiveTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val live by liveFixture(project)

  private val h get() = live.h
  private val results get() = Results.getInstance(project.get())
  private val large get() = LiveApp.largeTable

  private fun run(test: suspend CoroutineScope.() -> Unit) = timeoutRunBlocking(4.minutes) { test() }

  private suspend fun open(name: String, text: String): Editor =
    withContext(Dispatchers.EDT) { fixture.configureByText(name, text).let { fixture.editor } }

  /** Opens [name] and waits until the server has it: then its own requests answer for it. */
  private suspend fun opened(name: String, text: String): VirtualFile {
    val file = open(name, text).virtualFile!!
    h.core.awaitServerOpened(file)
    return file
  }

  /** A table other than the large one, and an index, as the explorer lists them for the bound session. */
  private suspend fun listed(): Pair<String, String> {
    val (tables, indices) = explorerRows()
    return (tables.firstOrNull { it != large } ?: error("no table but $large")) to (indices.firstOrNull() ?: error("no index"))
  }

  private suspend fun explorerRows(): Pair<List<String>, List<String>> {
    var tree = emptyList<Node>()
    // A slow OpenSearch target can take 30 s.
    waitFor("the tables and the indices", 60_000) { Explorer.getInstance(project.get()).tree().also { tree = it }.size == 2 }
    fun group(label: String) = tree.filterIsInstance<Node.Group>().single { it.label == label }.children
    val tables = group("DynamoDB").filterIsInstance<Node.Table>().map { it.name }
    val indices = group("OpenSearch").filterIsInstance<Node.Target>().flatMap { target -> target.children.filterIsInstance<Node.Index>().map { it.name } }
    return tables to indices
  }

  /** The first diagnostic the IDE shows in the fixture's file; `null` when none comes. */
  private suspend fun diagnostic(where: (HighlightInfo) -> Boolean = { true }): HighlightInfo? {
    var found: HighlightInfo? = null
    return try {
      awaitHighlighting(fixture, "a diagnostic") { infos -> infos.firstOrNull { it.description != null && where(it) }?.also { found = it } != null }
      found
    } catch (_: AssertionError) {
      null
    }
  }

  /** The editor's intentions at [offset] once one matches [wanted]; empty when none comes. */
  private suspend fun intentionsAt(offset: Int, wanted: (IntentionAction) -> Boolean): List<IntentionAction> {
    withContext(Dispatchers.EDT) { fixture.editor.caretModel.moveToOffset(offset) }
    var intentions = emptyList<IntentionAction>()
    return try {
      waitFor("the server's code actions") { withContext(Dispatchers.EDT) { fixture.availableIntentions }.also { intentions = it }.any(wanted) }
      intentions
    } catch (_: AssertionError) {
      emptyList()
    }
  }

  /** What the server answers for [key] that only the server can know: empty when it gives nothing. */
  private suspend fun probe(key: String, client: LspClient, file: VirtualFile, cell: Cell): List<Any> {
    val id = client.getDocumentIdentifier(file)
    val at = Position(cell.line, cell.character)
    return when (key) {
      // Through the IDE: word completion offers the buffer's own words, so only a label the text lacks counts.
      "completionProvider" -> withContext(Dispatchers.EDT) {
        fixture.completeBasic()
        fixture.lookupElements.orEmpty().map { LookupElementPresentation.renderElement(it).itemText.orEmpty() }
      }.filter { it.isNotEmpty() && it !in cell.text }
      // As VS Code asks: over the whole buffer, with the published diagnostics, once they show.
      "codeActionProvider" -> {
        diagnostic() ?: return emptyList()
        val published = (client.descriptor as DynoTableClientDescriptor).published[id.uri].orEmpty()
        val params = CodeActionParams(id, whole(file), CodeActionContext(published))
        client.sendRequest { it.textDocumentService.codeAction(params) }.orEmpty()
      }
      "hoverProvider" -> listOfNotNull(client.sendRequest { it.textDocumentService.hover(HoverParams(id, at)) })
      "documentFormattingProvider" -> client.sendRequest { it.textDocumentService.formatting(DocumentFormattingParams(id, FormattingOptions(2, true))) }.orEmpty()
      "definitionProvider" -> client.sendRequest { it.textDocumentService.definition(DefinitionParams(id, at)) }?.let { it.left ?: it.right }.orEmpty()
      // The IDE adds textual highlights on its own; a read or a write one comes from the server.
      "documentHighlightProvider" -> client.sendRequest { it.textDocumentService.documentHighlight(DocumentHighlightParams(id, at)) }.orEmpty()
        .filter { it.kind != null && it.kind != DocumentHighlightKind.Text }
      "renameProvider" -> client.sendRequest { it.textDocumentService.rename(RenameParams(id, at, "z")) }
        ?.let { edit -> edit.changes.orEmpty().values.flatten() + edit.documentChanges.orEmpty() }.orEmpty()
      "codeLensProvider" -> client.sendRequest { it.textDocumentService.codeLens(CodeLensParams(id)) }.orEmpty()
      "inlayHintProvider" -> {
        val whole = whole(file)
        client.sendRequest { it.textDocumentService.inlayHint(InlayHintParams(id, whole)) }.orEmpty()
      }
      else -> error("no probe for $key")
    }
  }

  private suspend fun whole(file: VirtualFile): Range {
    val document = readAction { FileDocumentManager.getInstance().getDocument(file)!! }
    return readAction { getLsp4jRange(document, 0, document.textLength) }
  }

  /** Every capability the server advertises answers, for files the plugin opened with this dialect's language id. */
  private suspend fun sweep(dialect: String) {
    live.bind()
    val cells = cellsOf(dialect, if (dialect == "opensearch") listed().second else "")
    val client = h.core.serving()!!
    val capabilities = MessageJsonHandler(emptyMap()).gson.toJsonTree(client.initializeResult!!.capabilities).asJsonObject
    val advertised = capabilities.keySet().filter { it.endsWith("Provider") && it != "executeCommandProvider" }
    val missing = mutableListOf<String>()
    var n = 0
    // Pushed, so not a capability key.
    opened("cap-${n++}${cells.suffix}", cells.diagnostics.text)
    if (diagnostic() == null) missing += "diagnostics"
    for (key in advertised) {
      if (key !in cells.probes) {
        missing += "$key: no probe"
        continue
      }
      val cell = cells.probes[key]
      val file = opened("cap-${n++}${cells.suffix}", (cell ?: cells.diagnostics).withCaret())
      val answer = try {
        probe(key, client, file, cell ?: cells.diagnostics)
      } catch (e: ResponseErrorException) {
        // Where the spec gives no answer, the server must still not fail.
        missing += "$key: failed: ${e.message}"
        continue
      }
      if (cell != null && answer.isEmpty()) missing += "$key: no answer"
    }
    assertEquals(emptyList<String>(), missing)
  }

  @Test
  fun `a bound session's app version meets the minimum, and a statement's plan shows in its lens row`() = run {
    live.bind()
    val version = h.core.serving()!!.initializeResult!!.serverInfo?.version
    assertTrue(meetsMinimum(version, MIN_APP_VERSION), "serverInfo.version $version")
    val editor = open("plan.dynotable.sql", "select o.orderId from \"$ORDERS\" o where o.status = 'x'")
    waitFor("the plan in the lens row", START_MS) { lensRow(editor, PLAN_PROVIDER_ID).isNotEmpty() }
  }

  @Test
  fun `an unbound Run shows the profile-required sentence once`() = run {
    val editor = open("unbound.dynotable.sql", "SELECT * FROM \"$large\"")
    waitFor("unbound, now ${h.core.status}", START_MS) { h.core.status is Status.Unbound }
    live.run(editor)
    delay(500)
    assertEquals(listOf(live.sentence("error-profile-required.json")), h.sentences())
  }

  @Test
  fun `choosing the live profile in the profile picker connects with it`() = run {
    val row = live.bind()
    assertEquals(RequestedProfile(row.id, row.name), DynoTableWorkspaceSettings.getInstance(project.get()).profile)
  }

  @Test
  fun `the explorer lists the profile's tables and indices`() = run {
    live.bind()
    val (tables, indices) = explorerRows()
    assertTrue(large in tables, "$tables")
    assertTrue(indices.isNotEmpty(), "no index")
  }

  @Test
  fun `a Run in SQL, PartiQL and OpenSearch each shows rows`() = run {
    live.bind()
    val (table, index) = listed()
    val queries = listOf(
      "run.dynotable.sql" to "SELECT * FROM \"$table\"",
      "run.partiql" to "SELECT * FROM \"$table\"",
      "run.opensearch" to "// dynotable: index=$index\n{\"query\": {\"match_all\": {}}}",
    )
    for ((name, text) in queries) {
      h.notifications.clear()
      val before = results.view
      live.run(open(name, text))
      assertEquals(emptyList<String>(), h.sentences(), name)
      assertNotSame(before, results.view, name)
      assertTrue(live.result().shown.cells.isNotEmpty(), "$name returned no rows")
    }
  }

  // A headless app has no window open on any profile.
  @Test
  fun `Open with no app window on the profile shows the profile-window-required sentence once`() = run {
    live.bind()
    val editor = open("open.dynotable.sql", "SELECT * FROM \"$ORDERS\"")
    h.notifications.clear()
    ServerCommands.getInstance(project.get()).open(editor)
    delay(500)
    assertEquals(listOf(live.sentence("error-profile-window-required.json")), h.sentences())
  }

  // A run that finishes after the cancel must be refused, never answered with rows.
  private suspend fun CoroutineScope.cancelAtOnce(name: String) {
    live.bind()
    val editor = open(name, "SELECT * FROM \"$large\"")
    h.notifications.clear()
    val job = launch { live.run(editor) }
    waitFor("the running view") { results.view is View.Running }
    (results.view as? View.Running)?.let { results.cancel(it.runId) }
    job.join()
    assertEquals(View.Cancelled, results.view)
    delay(500)
    assertEquals(emptyList<String>(), h.sentences())
  }

  @Test
  fun `a SQL run cancelled at once shows the cancelled view and no notification`() = run { cancelAtOnce("cancel.dynotable.sql") }

  @Test
  fun `a PartiQL run cancelled at once shows the cancelled view and no notification`() = run { cancelAtOnce("cancel.partiql") }

  // The plugin applies the server's edit itself (Decision 4); the real server's edit form must pass its checks.
  @Test
  fun `the PartiQL quick fix Use brackets turns IN ('a') into square brackets`() = run {
    val text = "SELECT * FROM \"$ORDERS\" WHERE \"status\" IN ('a')"
    open("fix.partiql", text)
    val parens = diagnostic { text.substring(it.startOffset, it.endOffset) == "('a')" }
    assertNotNull(parens, "no diagnostic on the parentheses")
    val fix = intentionsAt(parens!!.startOffset) { it.text == "Use brackets" }.firstOrNull { it.text == "Use brackets" }
    assertNotNull(fix, "no Use brackets fix")
    withContext(Dispatchers.EDT) { fixture.launchAction(fix!!) }
    assertEquals("SELECT * FROM \"$ORDERS\" WHERE \"status\" IN ['a']", withContext(Dispatchers.EDT) { fixture.editor.document.text })
  }

  // The buffer holds no table name, so only the server's own listing can offer them.
  @Test
  fun `completion invoked after FROM lists the seeded tables`() = run {
    live.bind()
    opened("from.dynotable.sql", "SELECT * FROM <caret>")
    var items = emptyList<String>()
    runCatching {
      waitFor("the tables among the items", START_MS) {
        items = withContext(Dispatchers.EDT) {
          fixture.completeBasic()
          fixture.lookupElements.orEmpty().map { LookupElementPresentation.renderElement(it).itemText.orEmpty() }
        }
        items.containsAll(listOf(ORDERS, CUSTOMERS))
      }
    }
    assertTrue(items.containsAll(listOf(ORDERS, CUSTOMERS)), "$items")
  }

  @Test
  fun `SQL GROUP BY customerId answers the columns customerid and COUNT`() = run {
    live.bind()
    h.notifications.clear()
    live.run(open("group.dynotable.sql", "SELECT customerId, COUNT(*) FROM \"$ORDERS\" GROUP BY customerId"))
    assertEquals(emptyList<String>(), h.sentences())
    assertEquals(listOf("customerid", "COUNT(*)"), live.result().run.columns)
  }

  @Test
  fun `the Run lens of the second statement runs the second statement`() = run {
    live.bind()
    val second = "SELECT COUNT(*) FROM \"$CUSTOMERS\""
    val editor = open("lens.dynotable.sql", "SELECT customerId FROM \"$ORDERS\";\n$second;")
    val lens = serverLenses(fixture, editor, START_MS).single { (line, entry) -> line == 1 && entry.text == "Run" }.second as ClickableTextCodeVisionEntry
    h.notifications.clear()
    withContext(Dispatchers.EDT) { lens.onClick(editor) }
    waitFor("the result", START_MS) { results.view is View.Result }
    assertEquals(emptyList<String>(), h.sentences())
    assertEquals(second, live.result().run.executedStatement)
  }

  @Test
  fun `Pick Index writes the listed index into the directive`() = run {
    live.bind()
    val (_, index) = listed()
    h.popupAnswer = "OpenSearch"
    NewQuery.getInstance(project.get()).open(null)
    val document = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project.get()).selectedTextEditor!!.document }
    assertEquals("// dynotable: index=\n", document.text)
    h.notifications.clear()
    h.popupAnswer = index
    IndexPicker.getInstance(project.get()).pick(document)
    assertEquals("// dynotable: index=$index\n", document.text)
    assertEquals(emptyList<String>(), h.sentences())
  }

  // The server answers with the sentence and also sends it as window/showMessage.
  @Test
  fun `Validate against the index shows one message`() = run {
    live.bind()
    val (_, index) = listed()
    val file = opened("validate.opensearch", "// dynotable: index=$index\n{\"query\": {\"match_all\": {}}}")
    val client = h.core.serving()!!
    val params = CodeActionParams(client.getDocumentIdentifier(file), Range(Position(0, 0), Position(1, 0)), CodeActionContext(emptyList()))
    val actions = client.sendRequest { it.textDocumentService.codeAction(params) }.orEmpty()
    val titles = actions.map { if (it.isLeft) it.left.title else it.right.title }
    val validate = actions.firstNotNullOfOrNull { if (it.isLeft) it.left.takeIf { c -> c.title == VALIDATE } else it.right.takeIf { a -> a.title == VALIDATE }?.command }
    assertNotNull(validate, "$titles")
    h.notifications.clear()
    ServerCommands.getInstance(project.get()).send(file, validate!!.command, validate.arguments.orEmpty())
    waitFor("the message") { h.notifications.isNotEmpty() }
    // A second notification would come now.
    delay(1_000)
    assertEquals(1, h.notifications.size, "${h.sentences()}")
  }

  @Test
  fun `every advertised capability answers in a SQL file`() = run { sweep("sql") }

  @Test
  fun `every advertised capability answers in a PartiQL file`() = run { sweep("partiql") }

  @Test
  fun `every advertised capability answers in an OpenSearch file`() = run { sweep("opensearch") }

  // A stop of the client mid-run sends no cancel; the app must end that run itself.
  @Test
  fun `after the client stops mid-run, the next run is not refused as busy`() = run {
    live.bind()
    val editor = open("dropped.dynotable.sql", "SELECT * FROM \"$large\"")
    val job = launch { live.run(editor) }
    waitFor("the running view") { results.view is View.Running }
    // Lets the request reach the app.
    delay(200)
    val attempt = h.core.session()!!
    h.platformStop(h.core.serving()!!)
    withTimeout(5_000) { job.join() }
    h.core.perform(Action.RECONNECT)
    waitFor("a new session, now ${h.core.status}", START_MS) { (h.core.status as? Status.Connected)?.let { it.attempt > attempt } == true }
    h.notifications.clear()
    val before = results.view
    live.run(open("after.dynotable.sql", "SELECT * FROM \"$large\""))
    assertEquals(emptyList<String>(), h.sentences())
    assertNotSame(before, results.view)
    live.result()
  }

  private companion object {
    const val VALIDATE = "Validate against the index"
  }
}
