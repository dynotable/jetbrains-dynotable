package com.dynotable.profiles

import com.dynotable.protocol.ProfileRow
import com.dynotable.ui.plain

const val EMPTY_ROSTER = "DynoTable has no profiles. Create a profile in DynoTable."
const val INCOMPLETE = "This profile is incomplete. Finish setting it up in DynoTable."
const val NOT_BINDABLE = "DynoTable cannot use this profile. Check it in DynoTable."

/** The id goes on the wire; the name is for display only. */
data class RequestedProfile(val id: String, val name: String)

/**
 * A popup row: [text] the name, [detail] the slug, the engines and the refusal. [refusal]: why the
 * row cannot be chosen; the server's `reason` is a code, not a sentence.
 */
data class ProfileItem(val text: String, val detail: String, val row: ProfileRow, val refusal: String?)

sealed interface Picker {
  data object Empty : Picker

  data class Pick(val items: List<ProfileItem>) : Picker
}

fun pickerOf(rows: List<ProfileRow>): Picker {
  if (rows.isEmpty()) return Picker.Empty
  return Picker.Pick(
    rows.map { row ->
      val refusal = when {
        row.bindable -> null
        row.reason == "incomplete" -> INCOMPLETE
        else -> NOT_BINDABLE
      }
      val engines = listOfNotNull("DynamoDB".takeIf { row.engines.dynamodb }, "OpenSearch".takeIf { row.engines.opensearch }).joinToString(", ")
      val detail = listOf(row.slug, engines, refusal.orEmpty()).filter { it.isNotEmpty() }.joinToString(" — ")
      ProfileItem(plain(row.name), plain(detail), row, refusal)
    },
  )
}
