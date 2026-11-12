package dspy4s.harness.host

/** What the host of an open session does now, apart from the journal, for a client (CODE_HARNESS_DESIGN.md, 10.6). A
  * client polls it: it is not an entry, and it does not go into the journal.
  */
enum Activity derives CanEqual:

  /** The walk of the scope for secret files (5.4): the folders that it read, and the folders of the walk before it, if
    * any. A command waits for this walk.
    */
  case SecretScan(folders: Long, total: Option[Long])

  /** The summaries of the memory tree before a call to the policy (7.1): the summaries that ended, of `total`. The call
    * to the policy waits for them.
    */
  case MemorySummaries(done: Int, total: Int)

/** The size of the last view of the memory that a call to the policy got, and its budget, in characters (7.1). */
final case class MemoryUse(chars: Int, budget: Int) derives CanEqual
