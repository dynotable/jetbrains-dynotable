package com.dynotable.explorer

import com.dynotable.connect.ConnectCore
import com.dynotable.connect.DynoTableServer
import com.dynotable.editor.Answer
import com.dynotable.editor.ServerCommands
import com.dynotable.editor.UNREADABLE
import com.dynotable.editor.Unanswered
import com.dynotable.protocol.ListIndicesResult
import com.dynotable.protocol.ListTablesResult
import com.dynotable.protocol.ProtocolException
import com.dynotable.query.NewQuery
import com.dynotable.refusals.Failure
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

const val EXPLORER_TOOL_WINDOW = "DynoTable"

/** The explorer row that New Query acts on. */
val EXPLORER_NODE: DataKey<Node> = DataKey.create("DynoTable.ExplorerNode")

/** [parse] the answer, or a [Listing.Failed] that says why it cannot be read. */
fun <T> listingOf(answer: Any?, parse: (Any?) -> T): Listing<T> = try {
  Listing.Ok(parse(answer))
} catch (_: ProtocolException) {
  Listing.Failed(UNREADABLE)
}

/**
 * The current session's listings. Indices are fetched when the tree is first drawn. Tables never
 * wait for them: a slow OpenSearch target can take 30 s to answer.
 */
@Service(Service.Level.PROJECT)
class Explorer(private val project: Project, internal val cs: CoroutineScope) {
  private val core get() = ConnectCore.getInstance(project)
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  private class Listings(val attempt: Int, val client: LspClient, val tables: Deferred<Listing<ListTablesResult>>) {
    val indicesRequested = AtomicBoolean()

    @Volatile
    var indices: Listing<ListIndicesResult>? = null
  }

  @Volatile
  private var listings: Listings? = null

  fun addListener(parent: Disposable, listener: () -> Unit) {
    listeners += listener
    Disposer.register(parent) { listeners -= listener }
  }

  /** The tree or its message can be other now. */
  fun changed() = listeners.forEach { it() }

  /** A new session, with the answer of its connect-time `dynotable/listTables`. */
  fun connected(attempt: Int, client: LspClient, tables: Deferred<Listing<ListTablesResult>>) {
    listings = Listings(attempt, client, tables)
    changed()
  }

  /** For a caller on the EDT, which must not wait. */
  fun launchRefresh() {
    cs.launch { refresh() }
  }

  /** Lists again with `fresh: true`: without it, the server answers from its cache. */
  suspend fun refresh() {
    core.client() ?: return
    val current = listings?.takeIf { it.attempt == core.session() } ?: return
    val next = Listings(current.attempt, current.client, cs.async { tables(current.client) })
    listings = next
    next.indicesRequested.set(true)
    requestIndices(next, fresh = true)
    changed()
  }

  /** The tree message: why the tree is empty. */
  fun message(): String? = when (val status = core.status) {
    is Status.Starting -> "Connecting to DynoTable…"
    is Status.Unbound -> Reason.UNBOUND.sentence
    is Status.Disconnected -> status.reason.sentence
    else -> null
  }

  /** The top rows. Empty while no bound session serves, and for a replaced session's listing. */
  suspend fun tree(): List<Node> {
    val current = listings ?: return emptyList()
    if (!serves(current)) return emptyList()
    if (current.indicesRequested.compareAndSet(false, true)) requestIndices(current, fresh = false)
    val tables = current.tables.await()
    // A newer session or an unbound answer arrived meanwhile; its own redraw follows.
    if (current !== listings || !serves(current)) return emptyList()
    return treeOf(tables, current.indices)
  }

  private fun serves(listings: Listings) = core.status == Status.Connected(listings.attempt)

  private suspend fun tables(client: LspClient) = listing(client, ListTablesResult::from) { it.listTables(fresh()) }

  /** The OpenSearch group shows when its own listing arrives. */
  private fun requestIndices(listings: Listings, fresh: Boolean) {
    cs.launch {
      listings.indices = listing(listings.client, ListIndicesResult::from) { it.listIndices(if (fresh) fresh() else JsonObject()) }
      if (listings === this@Explorer.listings) changed()
    }
  }

  private suspend fun <T> listing(client: LspClient, parse: (Any?) -> T, sender: (DynoTableServer) -> CompletableFuture<JsonElement>): Listing<T> =
    when (val reply = ServerCommands.getInstance(project).reply(client, sender)) {
      is Answer -> listingOf(reply.value, parse)
      is Unanswered -> Listing.Failed((reply.failure as? Failure.Error)?.error?.message ?: Reason.CONNECTION_ENDED.sentence)
    }

  private fun fresh() = JsonObject().apply { addProperty("fresh", true) }

  companion object {
    fun getInstance(project: Project): Explorer = project.service()
  }
}

class ExplorerToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = ExplorerPanel(project)
    toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false).apply { setDisposer(panel) })
    // The content is made when the tool window first shows, also when it was open at startup.
    ConnectCore.getInstance(project).trigger()
  }
}

/** Showing the explorer is a connect trigger. */
class ExplorerShown(private val project: Project) : ToolWindowManagerListener {
  override fun toolWindowShown(toolWindow: ToolWindow) {
    if (toolWindow.id == EXPLORER_TOOL_WINDOW) ConnectCore.getInstance(project).trigger()
  }
}

/** Every server name shows as plain text: the renderer draws text fragments, never Swing HTML. */
class ExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable, UiDataProvider {
  private val explorer = Explorer.getInstance(project)
  private val scope = explorer.cs.childScope("DynoTable explorer")
  private var drawing: Job? = null

  internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
    isRootVisible = false
    cellRenderer = Renderer()
  }

  init {
    setContent(ScrollPaneFactory.createScrollPane(tree))
    val actions = ActionManager.getInstance()
    toolbar = actions.createActionToolbar(PLACE, DefaultActionGroup(actions.getAction(REFRESH_ACTION)), true)
      .also { it.targetComponent = this }.component
    PopupHandler.installPopupMenu(tree, DefaultActionGroup(actions.getAction(NEW_QUERY_ACTION)), PLACE)
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        open(selected() ?: return false)
        return true
      }
    }.installOn(tree)
    DumbAwareAction.create { selected()?.let(::open) }.registerCustomShortcutSet(CustomShortcutSet(KeyEvent.VK_ENTER), tree, this)
    explorer.addListener(this) { ApplicationManager.getApplication().invokeLater(::draw) }
    draw()
  }

  /** Draws the latest tree; a newer call cancels an older one that still waits for its listing. */
  private fun draw() {
    drawing?.cancel()
    drawing = scope.launch {
      val nodes = explorer.tree()
      val message = explorer.message()
      withContext(Dispatchers.EDT) {
        val root = DefaultMutableTreeNode()
        nodes.forEach { root.add(treeNode(it)) }
        tree.model = DefaultTreeModel(root)
        tree.emptyText.text = message.orEmpty()
        TreeUtil.expandAll(tree)
      }
    }
  }

  private fun selected(): Node? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node

  private fun open(node: Node) {
    if (node is Node.Table || node is Node.Index) NewQuery.getInstance(project).launch(node)
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[EXPLORER_NODE] = selected()
  }

  override fun dispose() = scope.cancel()

  private class Renderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      val node = (value as? DefaultMutableTreeNode)?.userObject as? Node ?: return
      icon = when (node) {
        is Node.Group -> AllIcons.Nodes.DataSchema
        is Node.Target -> AllIcons.Nodes.Folder
        is Node.Table -> AllIcons.Nodes.DataTables
        is Node.Index -> AllIcons.Actions.Search
        is Node.Message -> AllIcons.General.Warning
      }
      append(node.label)
    }
  }

  private companion object {
    const val PLACE = "DynoTableExplorer"
  }
}

private fun treeNode(node: Node): DefaultMutableTreeNode = DefaultMutableTreeNode(node).apply {
  when (node) {
    is Node.Group -> node.children.forEach { add(treeNode(it)) }
    is Node.Target -> node.children.forEach { add(treeNode(it)) }
    else -> {}
  }
}

const val REFRESH_ACTION = "DynoTable.RefreshExplorer"
const val NEW_QUERY_ACTION = "DynoTable.NewQuery"

class RefreshExplorerAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    Explorer.getInstance(e.project ?: return).launchRefresh()
  }
}
