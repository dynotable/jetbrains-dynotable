package com.dynotable.actions

import com.dynotable.connect.Platform

/**
 * The `sqlFiles` glob has no backslash escape. A one-character class matches the character literally;
 * a `]` is literal only as the first character of a class. The settings page trims each pattern, so
 * leading and trailing whitespace goes into classes too.
 */
private fun escapeGlob(path: String): String =
  path
    .replace(Regex("""[\[\]{}*?]""")) { "[${it.value}]" }
    .replace(Regex("""^\s+|\s+$""")) { space -> space.value.map { "[$it]" }.joinToString("") }

/**
 * [sqlFiles] with the path of [file] relative to [baseDir] appended as a literal glob; null when no
 * project base dir holds the file.
 */
fun attach(platform: Platform, baseDir: String?, file: String, sqlFiles: List<String>): List<String>? {
  if (baseDir == null) return null
  val slashes = { path: String -> if (platform == Platform.WINDOWS) path.replace('\\', '/') else path }
  val prefix = slashes(baseDir).trimEnd('/') + "/"
  val path = slashes(file)
  if (!path.startsWith(prefix)) return null
  return sqlFiles + escapeGlob(path.substring(prefix.length))
}
