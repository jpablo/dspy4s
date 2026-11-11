package dspy4s.harness.host

/** What the host of an open session does now, apart from the journal, for a client (CODE_HARNESS_DESIGN.md, 10.6). A
  * client polls it: it is not an entry, and it does not go into the journal.
  */
enum Activity derives CanEqual:

  /** The walk of the scope for secret files (5.4): the folders that it read, and the folders of the walk before it, if
    * any. A command waits for this walk.
    */
  case SecretScan(folders: Long, total: Option[Long])
