package com.dynotable.files

import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/** The candidates for `sqlFiles`: a file with the `.sql` extension, in any case. */
val VirtualFile.isSql: Boolean get() = "sql".equals(extension, ignoreCase = true)

/** [file]'s path relative to [baseDir]; `null` when [baseDir] does not hold it. Both use `/`. */
fun relativePath(baseDir: String, file: String): String? {
  val prefix = baseDir.trimEnd('/') + "/"
  return if (file.startsWith(prefix)) file.substring(prefix.length) else null
}

/**
 * The `sqlFiles` glob match, with the VS Code glob rules: `*` and `?` stay in one path segment, a
 * `**` segment matches zero or more segments (so `**` + `/a.sql` also matches `a.sql` at the root),
 * `[...]` is a character class (`!` or `^` negates it, a `]` first in it is literal), `{a,b}` is a
 * group. There is no backslash escape. Paths use `/`.
 *
 * The patterns come from a shared project file and the file type overrider can run on the EDT, so the
 * match is a state-set simulation in O(pattern × path) time, not a backtracking regex.
 */
object SqlGlobs {
  /** A file type overrider asks for every file, so each pattern compiles once. */
  private val compiled = ConcurrentHashMap<String, Program>()

  /** Matches nothing. */
  private val INVALID = Program(emptyList())

  /**
   * True when [file] is under [baseDir] and its path relative to [baseDir] matches one of [patterns].
   * A pattern with a class that is not valid (as `[z-a]`) matches nothing.
   */
  fun matches(patterns: List<String>, baseDir: String, file: String): Boolean {
    val relative = relativePath(baseDir, file) ?: return false
    return patterns.any { compile(it).matches(relative) }
  }

  private fun compile(glob: String) = compiled.getOrPut(glob) {
    try {
      val code = mutableListOf<Inst>()
      emit(glob, code)
      code += Inst.Accept
      Program(code)
    } catch (_: InvalidClass) {
      INVALID
    }
  }

  private sealed interface Inst {
    class Step(val test: (Char) -> Boolean) : Inst
    class Split(var first: Int, var second: Int) : Inst
    class Jump(var target: Int) : Inst
    data object Accept : Inst
  }

  private class InvalidClass : RuntimeException()

  private class Program(private val code: List<Inst>) {
    fun matches(text: String): Boolean {
      var current = closure(listOf(0))
      for (char in text) {
        if (current.isEmpty()) return false
        current = closure(current.mapNotNull { pc -> (code[pc] as? Inst.Step)?.takeIf { it.test(char) }?.let { pc + 1 } })
      }
      return current.any { code[it] == Inst.Accept }
    }

    /** The Step and Accept instructions reachable from [starts] without reading a character. */
    private fun closure(starts: List<Int>): List<Int> {
      if (code.isEmpty()) return emptyList()
      val seen = BooleanArray(code.size)
      val out = mutableListOf<Int>()
      val stack = ArrayDeque(starts)
      while (stack.isNotEmpty()) {
        val pc = stack.removeLast()
        if (seen[pc]) continue
        seen[pc] = true
        when (val inst = code[pc]) {
          is Inst.Split -> {
            stack += inst.second
            stack += inst.first
          }
          is Inst.Jump -> stack += inst.target
          else -> out += pc
        }
      }
      return out
    }
  }

  private val notSlash = { char: Char -> char != '/' }

  /** `[^/]*`, or `.*` with [test] = any character. */
  private fun emitStar(code: MutableList<Inst>, test: (Char) -> Boolean) {
    val loop = code.size
    code += Inst.Split(loop + 1, loop + 3)
    code += Inst.Step(test)
    code += Inst.Jump(loop)
  }

  private fun emit(glob: String, code: MutableList<Inst>) {
    var i = 0
    while (i < glob.length) {
      val char = glob[i]
      val segmentStart = i == 0 || glob[i - 1] == '/'
      when {
        char == '*' && glob.startsWith("**", i) && segmentStart && (i + 2 == glob.length || glob[i + 2] == '/') -> {
          if (i + 2 == glob.length) {
            emitStar(code) { true }
            i += 2
          } else {
            // (?:[^/]*/)*
            val loop = code.size
            val exit = Inst.Split(loop + 1, -1)
            code += exit
            emitStar(code, notSlash)
            code += Inst.Step { it == '/' }
            code += Inst.Jump(loop)
            exit.second = code.size
            i += 3
          }
        }
        char == '*' -> {
          emitStar(code, notSlash)
          while (i < glob.length && glob[i] == '*') i++
        }
        char == '?' -> {
          code += Inst.Step(notSlash)
          i++
        }
        char == '[' -> i = characterClass(glob, i, code)
        char == '{' -> {
          val end = glob.indexOf('}', i + 1)
          if (end < 0) {
            code += Inst.Step { it == '{' }
            i++
          } else {
            val jumps = mutableListOf<Inst.Jump>()
            val alternatives = glob.substring(i + 1, end).split(',')
            for ((index, alternative) in alternatives.withIndex()) {
              val split = if (index < alternatives.lastIndex) Inst.Split(code.size + 1, -1).also { code += it } else null
              emit(alternative, code)
              if (split != null) {
                jumps += Inst.Jump(-1).also { code += it }
                split.second = code.size
              }
            }
            jumps.forEach { it.target = code.size }
            i = end + 1
          }
        }
        else -> {
          code += Inst.Step { it == char }
          i++
        }
      }
    }
  }

  /** Emits the class that starts at [start] (a `[`) and returns the index after it. */
  private fun characterClass(glob: String, start: Int, code: MutableList<Inst>): Int {
    var i = start + 1
    val negated = i < glob.length && (glob[i] == '!' || glob[i] == '^')
    if (negated) i++
    val bodyStart = i
    if (i < glob.length && glob[i] == ']') i++
    val end = glob.indexOf(']', i)
    if (end < 0) {
      code += Inst.Step { it == '[' }
      return start + 1
    }
    val body = glob.substring(bodyStart, end)
    val ranges = mutableListOf<CharRange>()
    var j = 0
    while (j < body.length) {
      if (j + 2 < body.length && body[j + 1] == '-') {
        if (body[j] > body[j + 2]) throw InvalidClass()
        ranges += body[j]..body[j + 2]
        j += 3
      } else {
        ranges += body[j]..body[j]
        j++
      }
    }
    code += Inst.Step { char -> if (negated) char != '/' && ranges.none { char in it } else ranges.any { char in it } }
    return end + 1
  }
}
