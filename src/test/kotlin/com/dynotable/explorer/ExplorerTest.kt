package com.dynotable.explorer

import com.dynotable.editor.ServerCommands
import com.dynotable.files.DynoTableSqlFileType
import com.dynotable.files.OpenSearchFileType
import com.dynotable.harness.PopupRow
import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.profiles.ProfilePicker
import com.dynotable.query.IndexPicker
import com.dynotable.query.NewQuery
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicHTML
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.time.Duration.Companion.minutes

/** The explorer tool window, New Query and Pick Index, against the fake server. */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class ExplorerTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  private val explorer get() = Explorer.getInstance(project.get())
  private val newQuery get() = NewQuery.getInstance(project.get())

  private fun run(test: suspend CoroutineScope.() -> Unit) = timeoutRunBlocking(2.minutes) { test() }

  private suspend fun connected(): Int {
    waitFor("connected, now ${h.core.status}") { h.core.status is Status.Connected }
    return (h.core.status as Status.Connected).attempt
  }

  /** Connects as a shown explorer does, and waits for the first full tree. */
  private suspend fun listed(): List<Node> {
    h.core.trigger()
    connected()
    var tree = emptyList<Node>()
    waitFor("the tables and the indices") { explorer.tree().also { tree = it }.size == 2 }
    return tree
  }

  private fun group(tree: List<Node>, label: String) = tree.filterIsInstance<Node.Group>().single { it.label == label }.children

  /** The platform makes the content when the tool window first shows. */
  private suspend fun showExplorer(): ExplorerPanel = withContext(Dispatchers.EDT) {
    val window = (ToolWindowManager.getInstance(project.get()) as ToolWindowHeadlessManagerImpl).doRegisterToolWindow(EXPLORER_TOOL_WINDOW)
    ExplorerToolWindowFactory().createToolWindowContent(project.get(), window)
    window.contentManager.contents.single().component as ExplorerPanel
  }

  /** The headless tool window, with the id it does not have. */
  private fun explorerWindow(): ToolWindow {
    val window = ToolWindowManager.getInstance(project.get()).getToolWindow(EXPLORER_TOOL_WINDOW)!!
    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ToolWindow::class.java)) { _, method, args ->
      if (method.name == "getId") EXPLORER_TOOL_WINDOW else method.invoke(window, *args.orEmpty())
    } as ToolWindow
  }

  /** The panel's rows, two spaces per level. */
  private suspend fun rows(panel: ExplorerPanel): List<String> = withContext(Dispatchers.EDT) {
    fun walk(node: DefaultMutableTreeNode, depth: Int): List<String> =
      node.children().toList().flatMap { child ->
        child as DefaultMutableTreeNode
        listOf("  ".repeat(depth) + (child.userObject as Node).label) + walk(child, depth + 1)
      }
    walk(panel.tree.model.root as DefaultMutableTreeNode, 0)
  }

  private suspend fun editor(): Editor = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project.get()).selectedTextEditor!! }

  private suspend fun openFiles() = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project.get()).openFiles.size }

  // lsp4j delivers empty params as `null`.
  private fun sent(method: String) = h.fake.received.filter { it.method == method }.map { it.params as? JsonObject ?: JsonObject() }

  private fun indices(vararg rows: Pair<String, String>) = JsonParser.parseString(
    """{"indices":[${rows.joinToString(",") { """{"name":"${it.first}","target":"${it.second}"}""" }}],"unreachable":[]}""",
  )

  @Test
  fun `showing the explorer connects and draws the tables and indices, and showing it again after a Stop reconnects`() = run {
    h.fake.start()
    val panel = showExplorer()
    val attempt = connected()
    val expected = listOf("DynamoDB", "  customers", "  large-products", "  orders", "  set-types", "OpenSearch", "  target-1") +
      listOf("analytics", "customers", "large-products", "orders", "products", "products-vectors").map { "    $it" }
    waitFor("the drawn tree, now ${rows(panel)}") { rows(panel) == expected }

    h.platformStop(h.clients().single())
    h.awaitStatus(Status.Disconnected(Reason.STOPPED))
    project.get().messageBus.syncPublisher(ToolWindowManagerListener.TOPIC).toolWindowShown(explorerWindow())
    assertTrue(connected() > attempt)
  }

  // Without `fresh`, the server answers from its cache, so Refresh would show nothing new.
  @Test
  fun `Refresh sends fresh true to both listings`() = run {
    h.fake.start()
    listed()
    explorer.refresh()
    waitFor("both refreshed listings") {
      listOf("dynotable/listTables", "dynotable/listIndices").all { method -> sent(method).any { it["fresh"]?.asBoolean == true } }
    }
  }

  // A VPC-only OpenSearch domain off the VPN takes about 30 s to fail.
  @Test
  fun `tables show while the index listing is still in flight`() = run {
    val held = CompletableFuture<Any?>()
    h.fake.handle("dynotable/listIndices") { _, _ -> held }
    h.fake.start()
    h.core.trigger()
    connected()
    assertEquals(listOf("DynamoDB"), explorer.tree().map { it.label })
    held.complete(h.fake.fixture("list-indices.json"))
    waitFor("the OpenSearch group") { explorer.tree().map { it.label } == listOf("DynamoDB", "OpenSearch") }
  }

  @Test
  fun `drops a replaced session's listing`() = run {
    h.fake.start()
    listed()
    val held = CompletableFuture<Any?>()
    h.fake.handle("dynotable/listTables") { _, _ -> held }
    val asked = sent("dynotable/listTables").size
    explorer.refresh()
    val old = async { explorer.tree() }
    waitFor("the held listing") { sent("dynotable/listTables").size > asked }
    h.fake.unhandle("dynotable/listTables")
    val before = h.core.session()!!
    h.core.perform(Action.RECONNECT)
    waitFor("a new session") { (h.core.status as? Status.Connected)?.attempt?.let { it > before } == true }
    held.complete(JsonParser.parseString("""{"tables":["old-session"]}"""))
    assertEquals(emptyList<Node>(), old.await())
  }

  // Another profile has other tables; the old profile's tree must not stay.
  @Test
  fun `a profile change refetches`() = run {
    h.fake.start()
    listed()
    h.fake.handle("dynotable/listTables") { _, _ -> JsonParser.parseString("""{"tables":["after-switch"]}""") }
    h.popupAnswer = "profile-2-name"
    ProfilePicker.getInstance(project.get()).select()
    waitFor("the new profile's tables") { explorer.tree().firstOrNull() == Node.Group("DynamoDB", listOf(Node.Table("after-switch"))) }
  }

  // The `didOpen` URI is encoded (a space is `%20`); a Run sent with another spelling of it is refused.
  @Test
  fun `a table opens its SELECT star query in a scratch file the server opens, and Run there sends the didOpen URI`() = run {
    h.fake.start()
    val customers = group(listed(), "DynamoDB").first()
    newQuery.open(customers)
    val editor = editor()
    val file = editor.virtualFile!!
    assertEquals("SELECT * FROM \"customers\"", editor.document.text)
    assertTrue(ScratchFileService.findRootType(file) is ScratchRootType)
    assertEquals(DynoTableSqlFileType, file.fileType)
    assertTrue(" " in file.path, file.path)
    waitFor("the didOpen of the new query") { h.fake.openedUri(file.name) != null }
    val uri = h.fake.openedUri(file.name)!!

    ServerCommands.getInstance(project.get()).run(editor)
    val run = sent("workspace/executeCommand").last { it["command"].asString == RUN_STATEMENT }
    assertEquals(uri, run["arguments"].asJsonArray[0].asJsonObject["uri"].asString)
  }

  @Test
  fun `an index opens an OpenSearch query with its directive`() = run {
    h.fake.start()
    val tree = listed()
    val analytics = (group(tree, "OpenSearch").single() as Node.Target).children.first()
    newQuery.open(analytics)
    val editor = editor()
    assertEquals(OpenSearchFileType, editor.virtualFile!!.fileType)
    assertEquals("// dynotable: index=analytics\n", editor.document.text)
  }

  @Test
  fun `New Query on an index that cannot be pinned warns and opens nothing`() = run {
    h.fake.handle("dynotable/listIndices") { _, _ -> indices("logs" to "search 1", "logs" to "search-2") }
    h.fake.start()
    val tree = listed()
    val logs = group(tree, "OpenSearch").filterIsInstance<Node.Target>().single { it.label == "search 1" }.children.single()
    val files = openFiles()
    newQuery.open(logs)
    val warning = "\"logs\" is on more than one OpenSearch target, and the target name \"search 1\" contains a space, so it cannot be pinned."
    assertEquals(listOf(StringUtil.escapeXmlEntities(warning)), h.sentences())
    assertEquals(files, openFiles())
  }

  // A file name the DynoTable file types do not match gets no completion, lens or Run.
  @Test
  fun `New Query in SQL through the language picker opens a file the server opens as sql`() = run {
    h.fake.start()
    h.popupAnswer = "SQL"
    newQuery.open(null)
    assertEquals(listOf("SQL", "PartiQL", "OpenSearch"), h.popups.single().map { it.text })
    val file = editor().virtualFile!!
    waitFor("the didOpen of the new query") { h.fake.openedUri(file.name) != null }
    assertEquals("sql", h.fake.language(h.fake.openedUri(file.name)!!))
  }

  /** A New Query OpenSearch file, as the language picker opens it. */
  private suspend fun newOpenSearchQuery(): Editor {
    h.popupAnswer = "OpenSearch"
    newQuery.open(null)
    return editor().also { assertEquals("// dynotable: index=\n", it.document.text) }
  }

  @Test
  fun `Pick Index writes line 0 of a New Query OpenSearch file`() = run {
    h.fake.handle("dynotable/listIndices") { _, _ -> indices("products" to "search-1") }
    h.fake.start()
    val document = newOpenSearchQuery().document
    h.popupAnswer = "products"
    IndexPicker.getInstance(project.get()).pick(document)
    assertEquals("// dynotable: index=products\n", document.text)
  }

  // The listing came from the replaced session, maybe for another profile.
  @Test
  fun `Pick Index writes nothing when the session changes while the picker is open`() = run {
    h.fake.handle("dynotable/listIndices") { _, _ -> indices("products" to "search-1") }
    h.fake.start()
    val document = newOpenSearchQuery().document
    val attempt = connected()
    h.popupAnswer = "products"
    h.duringPopup = {
      h.core.perform(Action.RECONNECT)
      runBlocking { waitFor("the session to change") { h.core.status != Status.Connected(attempt) } }
    }
    IndexPicker.getInstance(project.get()).pick(document)
    assertEquals("// dynotable: index=\n", document.text)
  }

  @Test
  fun `Pick Index warns and writes nothing when no index is listed or the pick cannot be pinned`() = run {
    var answer = indices()
    h.fake.handle("dynotable/listIndices") { _, _ -> answer }
    h.fake.start()
    val document = newOpenSearchQuery().document
    val picker = IndexPicker.getInstance(project.get())
    picker.pick(document)
    answer = indices("logs" to "search 1", "logs" to "search-2")
    h.popupAnswer = "logs"
    picker.pick(document)
    val warning = "\"logs\" is on more than one OpenSearch target, and the target name \"search 1\" contains a space, so it cannot be pinned."
    assertEquals(listOf("DynoTable lists no OpenSearch index.", StringUtil.escapeXmlEntities(warning)), h.sentences())
    assertEquals("// dynotable: index=\n", document.text)
  }

  // A JLabel renders a text that starts with <html> as HTML, which can load an image from anywhere.
  @Test
  fun `a hostile table name shows as text in the tree`() = run {
    val hostile = """<html><img src="https://example.com/x.png">"""
    h.fake.handle("dynotable/listTables") { _, _ -> JsonParser.parseString("""{"tables":[${Gson().toJson(hostile)}]}""") }
    h.fake.start()
    val panel = showExplorer()
    waitFor("the hostile row, now ${rows(panel)}") { "  $hostile" in rows(panel) }
    withContext(Dispatchers.EDT) {
      val tree = panel.tree
      TreeUtil.expandAll(tree)
      val row = (0 until tree.rowCount).single { (tree.getPathForRow(it).lastPathComponent as DefaultMutableTreeNode).userObject == Node.Table(hostile) }
      val node = tree.getPathForRow(row).lastPathComponent
      val shown = tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, true, row, false) as JComponent
      assertNull(shown.getClientProperty(BasicHTML.propertyKey), "an HTML view")
      assertEquals(hostile, (shown as SimpleColoredComponent).getCharSequence(false).toString())
    }
  }

  @Test
  fun `Pick Index shows a hostile index and target name as text`() = run {
    val hostile = """<html><img src="https://example.com/x.png">"""
    h.fake.handle("dynotable/listIndices") { _, _ -> JsonParser.parseString("""{"indices":[{"name":${Gson().toJson(hostile)},"target":${Gson().toJson(hostile)}}],"unreachable":[]}""") }
    h.fake.start()
    val document = newOpenSearchQuery().document
    h.popups.clear()
    IndexPicker.getInstance(project.get()).pick(document)
    assertEquals(listOf(PopupRow("\u200B$hostile", true, "\u200B$hostile")), h.popups.single())
  }

  @Test
  fun `the unbound state shows its reason as the tree message`() = run {
    h.fake.handle("dynotable/listTables") { _, _ -> throw h.fake.refusal("error-profile-required.json") }
    h.fake.start()
    val panel = showExplorer()
    waitFor("unbound, now ${h.core.status}") { h.core.status is Status.Unbound }
    assertEquals(emptyList<Node>(), explorer.tree())
    val sentence = "Select a DynoTable profile to run queries and list tables."
    waitFor("the tree message") { withContext(Dispatchers.EDT) { panel.tree.emptyText.text } == sentence }
    assertEquals(emptyList<String>(), rows(panel))
  }
}
