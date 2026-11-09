package dspy4s.harness.kernel

import zio.blocks.schema.Schema

/** The id of a session (8.6). */
final case class SessionId(value: String) derives Schema, CanEqual

/** The id of a `Send` or a `Grant` (4.5). A repeat with a known id commits nothing. */
final case class RequestId(value: String) derives Schema, CanEqual

/** The id of a guest API call (4.1). `cell` is the `seq` of the `Acted` entry of the cell, and `n` counts the calls of
  * that cell.
  */
final case class EffectId(cell: Long, n: Int) derives Schema, CanEqual

/** The versions that a replay of a journal needs: the compiler and the guest API (4.1). */
final case class Versions(compiler: String, guestApi: String) derives Schema, CanEqual
