package com.dynotable.settings

import com.dynotable.connect.ConnectCore
import com.dynotable.profiles.RequestedProfile
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * The paths of this computer. They are application-level and do not roam: a shared project file
 * must not point the plugin at another app binary or another port file.
 */
@Service(Service.Level.APP)
@State(name = "DynoTable", storages = [Storage("dynotable.xml", roamingType = RoamingType.DISABLED)])
class DynoTableAppSettings : SimplePersistentStateComponent<DynoTableAppSettings.State>(State()) {
  class State : BaseState() {
    var appPath by string()
    var userDataDir by string()
  }

  var appPath: String
    get() = state.appPath.orEmpty()
    set(value) {
      state.appPath = value.ifEmpty { null }
    }

  var userDataDir: String
    get() = state.userDataDir.orEmpty()
    set(value) {
      if (value == userDataDir) return
      state.userDataDir = value.ifEmpty { null }
      // Another user data directory is another app: it applies at once.
      ProjectManager.getInstanceIfCreated()?.openProjects?.forEach { it.serviceIfCreated<ConnectCore>()?.userDataDirChanged() }
    }

  companion object {
    fun getInstance(): DynoTableAppSettings = service()
  }
}

/** Shared with the project (`.idea/dynotable.xml`). */
@Service(Service.Level.PROJECT)
@State(name = "DynoTable", storages = [Storage("dynotable.xml")])
class DynoTableProjectSettings(private val project: Project) : SimplePersistentStateComponent<DynoTableProjectSettings.State>(State()) {
  class State : BaseState() {
    var sqlFiles by list<String>()
  }

  /** Glob patterns, relative to the project base dir, of the `.sql` files that are DynoTable SQL. */
  var sqlFiles: List<String>
    get() = state.sqlFiles.toList()
    set(value) {
      if (value == state.sqlFiles) return
      state.sqlFiles = value.toMutableList()
      // The platform caches the file type of each file until a file types change.
      WriteAction.run<Throwable> { FileTypeManagerEx.getInstanceEx().makeFileTypesChange("DynoTable sqlFiles changed") {} }
      // Another set of files needs a new client.
      ConnectCore.getInstance(project).sqlFilesChanged()
    }

  companion object {
    fun getInstance(project: Project): DynoTableProjectSettings = project.service()
  }
}

/**
 * The requested profile, in `workspace.xml`: a profile id names a profile of this computer's app, so a
 * shared project file must not carry it.
 */
@Service(Service.Level.PROJECT)
@State(name = "DynoTableProfile", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DynoTableWorkspaceSettings : SimplePersistentStateComponent<DynoTableWorkspaceSettings.State>(State()) {
  class State : BaseState() {
    var profileId by string()
    var profileName by string()
  }

  var profile: RequestedProfile?
    get() = state.profileId?.let { RequestedProfile(it, state.profileName.orEmpty()) }
    set(value) {
      state.profileId = value?.id
      state.profileName = value?.name
    }

  companion object {
    fun getInstance(project: Project): DynoTableWorkspaceSettings = project.service()
  }
}
