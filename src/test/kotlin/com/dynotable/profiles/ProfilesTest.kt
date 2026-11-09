package com.dynotable.profiles

import com.dynotable.protocol.Engines
import com.dynotable.protocol.ProfileRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The rows of the profile picker. */
class ProfilesTest {
  private fun row(bindable: Boolean = true, reason: String? = null, name: String = "Production") =
    ProfileRow("id-1", name, "production", "online", bindable, reason, Engines(dynamodb = true, opensearch = false))

  private fun item(row: ProfileRow) = (pickerOf(listOf(row)) as Picker.Pick).items.single()

  // The user tells profiles apart by name, slug and engines, and must be able to bind this one.
  @Test
  fun `shows a bindable row by name, slug and engines, with nothing refused`() {
    val item = item(row().copy(engines = Engines(dynamodb = true, opensearch = true)))
    assertEquals(listOf("Production", "production — DynamoDB, OpenSearch", null), listOf(item.text, item.detail, item.refusal))
  }

  // An empty popup tells the user nothing.
  @Test
  fun `tells an empty roster to create a profile in DynoTable`() {
    assertEquals(Picker.Empty("DynoTable has no profiles. Create a profile in DynoTable."), pickerOf(emptyList()))
  }

  // `incomplete` is a code; the user needs a sentence, and must not bind an unbindable row.
  @Test
  fun `gives an incomplete row our sentence`() {
    val item = item(row(bindable = false, reason = "incomplete"))
    assertEquals("This profile is incomplete. Finish setting it up in DynoTable.", item.refusal)
    assertEquals("production — DynamoDB — This profile is incomplete. Finish setting it up in DynoTable.", item.detail)
  }

  // The lenient parser passes a newer server's reason; it must not read as `incomplete`.
  @Test
  fun `refuses a non-bindable row with an unknown reason in general words`() {
    assertEquals("DynoTable cannot use this profile. Check it in DynoTable.", item(row(bindable = false, reason = "expired")).refusal)
  }

  // A profile name is the user's text; the popup's label would render it as HTML.
  @Test
  fun `a name that starts with html shows as text`() {
    assertEquals("​<html><img src=x>", item(row(name = "<html><img src=x>")).text)
  }
}
