package dspy4s.harness.kernel

import zio.blocks.schema.Schema

/** The id of a session (8.6). */
final case class SessionId(value: String) derives Schema, CanEqual

object SessionId:

  /** True for a text that can be the id of a session (8.7): a letter or a digit, then at most 127 letters, digits, `.`,
    * `_` or `-`, and no `..`. An id names a file of the host and a ref of git, so a path, a space or a text that starts
    * with `-` or `.` is not an id. Each entry point of an id from outside the host checks it: the handler of
    * `Sessions`, the desk, `--session` and `/close replaced`.
    */
  def valid(text: String): Boolean = Form.matches(text) && !text.contains("..")

  // A letter or a digit of ASCII, then at most 127 letters, digits, dots, underscores or dashes.
  private val Form = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r

/** The id of a `Send` or a `Grant` (4.5). A repeat with a known id commits nothing. */
final case class RequestId(value: String) derives Schema, CanEqual

/** The id of a guest API call (4.1). `cell` is the `seq` of the `Acted` entry of the cell, and `n` counts the calls of
  * that cell.
  */
final case class EffectId(cell: Long, n: Int) derives Schema, CanEqual

/** The versions that a replay of a journal needs: the compiler and the guest API (4.1). */
final case class Versions(compiler: String, guestApi: String) derives Schema, CanEqual
