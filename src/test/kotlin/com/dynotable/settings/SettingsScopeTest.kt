package com.dynotable.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

// A cloned project's .idea/dynotable.xml must not point the plugin at another app binary or another port file.
class SettingsScopeTest {
  @Test
  fun `the shared project file holds only sqlFiles`() {
    val shared = JDOMUtil.load(
      """
      <component name="DynoTable">
        <option name="sqlFiles"><list><option value="db/*.sql"/></list></option>
        <option name="appPath" value="/tmp/hostile"/>
        <option name="userDataDir" value="/tmp/hostile"/>
      </component>
      """.trimIndent(),
    )
    val state = XmlSerializer.deserialize(shared, DynoTableProjectSettings.State::class.java)
    assertEquals(listOf("db/*.sql"), state.sqlFiles)
    val saved = JDOMUtil.write(XmlSerializer.serialize(state))
    assertFalse("hostile" in saved, saved)
  }

  @Test
  fun `appPath and userDataDir are application-level`() {
    assertArrayEquals(arrayOf(Service.Level.APP), DynoTableAppSettings::class.java.getAnnotation(Service::class.java).value)
  }
}
