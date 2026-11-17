package dspy4s.harness.kernel

import dspy4s.harness.guest.{Access, Mode}

import java.util.Locale

/** The capabilities of `mode` in words (5.2): `Files^` for change, `Files` for read, and no word for none, for example
  * `Files^, Shell^, Llm^ and Store^`. A mode with no capability gives `no capability`. `/config` and the inspector show
  * a mode with these words.
  */
def modeWords(mode: Mode): String =
  val parts = Vector(
    "Files"    -> mode.files,
    "Shell"    -> mode.shell,
    "Network"  -> mode.network,
    "Llm"      -> mode.llm,
    "Sessions" -> mode.sessions,
    "Children" -> mode.children,
    "Store"    -> mode.store
  ).collect {
    case (name, Access.Full) => s"$name^"
    case (name, Access.Read) => name
  }
  parts match
    case Vector()    => "no capability"
    case Vector(one) => one
    case more        => s"${more.init.mkString(", ")} and ${more.last}"

/** The limits of an activation as names and values, for example `time of a cell` and `30 s`. `/config` and the
  * inspector show these lines.
  */
def limitLines(limits: Limits): Vector[(String, String)] =
  val summaries =
    if limits.summaryCalls == 0 then "0 (no compress or merge call)" else grouped(limits.summaryCalls.toLong)
  Vector(
    "model calls of a turn"         -> grouped(limits.modelCalls.toLong),
    "time of a cell"                -> millisWords(limits.cellMillis),
    "guest API calls of a cell"     -> grouped(limits.callsPerCell.toLong),
    "cells of a REPL"               -> grouped(limits.cellsPerRepl.toLong),
    "time of a command"             -> s"${grouped(limits.commandSeconds.toLong)} s",
    "characters of an observation"  -> grouped(limits.observationChars.toLong),
    "characters of the memory"      -> grouped(limits.memoryChars.toLong),
    "summary calls of a turn"       -> summaries,
    "queries or children at a time" -> grouped(limits.batch.toLong),
    "depth of rlm"                  -> grouped(limits.depth.toLong)
  )

/** The sandbox of an activation as names and values. A path in the home folder `home` starts with `~`. */
def sandboxLines(sandbox: SandboxFacts, home: String): Vector[(String, String)] =
  def short(path: String): String =
    if home.nonEmpty && (path == home || path.startsWith(home + "/")) then "~" + path.drop(home.length) else path
  def list(values: Vector[String]): String = if values.isEmpty then "none" else values.mkString(", ")
  Vector(
    "backend"                -> sandbox.backend.getOrElse("none, so no command runs"),
    "hosts of the network"   -> list(sandbox.hosts),
    "closed paths"           -> list(sandbox.secretPaths.map(short).distinct),
    "closed names"           -> list(sandbox.secretNames.map(name => s"$name, and each $name.NAME")),
    "variables of a command" -> list(sandbox.env)
  )

/** `n` with a comma after each group of three digits, for example `10,000`. */
def grouped(n: Long): String = "%,d".formatLocal(Locale.ROOT, n)

/** A time in milliseconds as seconds, for example `30 s`, or as milliseconds when it is not a whole second. */
private def millisWords(millis: Long): String =
  if millis % 1000 == 0 then s"${grouped(millis / 1000)} s" else s"${grouped(millis)} ms"
