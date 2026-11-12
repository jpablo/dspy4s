package dspy4s.harness.host

/** What the host of an open session does now, apart from the journal, for a client (CODE_HARNESS_DESIGN.md, 10.6). A
  * client polls it: it is not an entry, and it does not go into the journal.
  */
enum Activity derives CanEqual:

  /** The summaries of the memory tree before a call to the policy (7.1): the summaries that ended, of `total`. The call
    * to the policy waits for them.
    */
  case MemorySummaries(done: Int, total: Int)

/** The size of the last view of the memory that a call to the policy got, and its budget, in characters (7.1). */
final case class MemoryUse(chars: Int, budget: Int) derives CanEqual

/** The prompt and completion tokens of the last model call of the last call to the policy, from the usage of its reply
  * (CLI_SCREEN.md, 14.4). A client shows the prompt tokens as the part of the context of the model that the session
  * uses.
  */
final case class ContextUse(prompt: Long, completion: Long) derives CanEqual
