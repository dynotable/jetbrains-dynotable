package com.dynotable.files

/**
 * The `sqlFiles` glob match, with the VS Code glob rules: `*` and `?` stay in one path segment, a
 * `**` segment matches zero or more segments (so `**` + `/a.sql` also matches `a.sql` at the root),
 * `[...]` is a character class (`!` or `^` negates it, a `]` first in it is literal), `{a,b}` is a
 * group. There is no backslash escape. Paths use `/`.
 */
object SqlGlobs {
  /** True when [file] is under [baseDir] and its path relative to [baseDir] matches one of [patterns]. */
  fun matches(patterns: List<String>, baseDir: String, file: String): Boolean {
    val prefix = baseDir.trimEnd('/') + "/"
    if (!file.startsWith(prefix)) return false
    val relative = file.substring(prefix.length)
    return patterns.any { Regex(toRegex(it)).matches(relative) }
  }

  private fun toRegex(glob: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < glob.length) {
      val char = glob[i]
      val segmentStart = i == 0 || glob[i - 1] == '/'
      when {
        char == '*' && glob.startsWith("**", i) && segmentStart && (i + 2 == glob.length || glob[i + 2] == '/') -> {
          if (i + 2 == glob.length) {
            out.append(".*")
            i += 2
          } else {
            out.append("(?:[^/]*/)*")
            i += 3
          }
        }
        char == '*' -> {
          out.append("[^/]*")
          while (i < glob.length && glob[i] == '*') i++
        }
        char == '?' -> {
          out.append("[^/]")
          i++
        }
        char == '[' -> i = characterClass(glob, i, out)
        char == '{' -> {
          val end = glob.indexOf('}', i + 1)
          if (end < 0) {
            out.append(Regex.escape("{"))
            i++
          } else {
            glob.substring(i + 1, end).split(',').joinTo(out, "|", "(?:", ")") { toRegex(it) }
            i = end + 1
          }
        }
        else -> {
          out.append(Regex.escape(char.toString()))
          i++
        }
      }
    }
    return out.toString()
  }

  /** Appends the class that starts at [start] (a `[`) and returns the index after it. */
  private fun characterClass(glob: String, start: Int, out: StringBuilder): Int {
    var i = start + 1
    val negated = i < glob.length && (glob[i] == '!' || glob[i] == '^')
    if (negated) i++
    val bodyStart = i
    if (i < glob.length && glob[i] == ']') i++
    val end = glob.indexOf(']', i)
    if (end < 0) {
      out.append(Regex.escape("["))
      return start + 1
    }
    out.append(if (negated) "[^/" else "[")
    for (char in glob.substring(bodyStart, end)) {
      out.append(if (char in "\\[]^&") "\\$char" else char)
    }
    out.append(']')
    return end + 1
  }
}
