// mode: controller

// The cells of the page site/docs/harness/sessions.md: what the controller writes to start, list, tell and observe
// work sessions.

// --8<-- [start:start-scratch]
val ref = start(
  "Write the file hello.txt with the text hello. Then submit its path.",
  Scope.Scratch(""),
  StrategyName.MemoryTree
)
println(s"Started the session ${ref.id}.")
// --8<-- [end:start-scratch]

// --8<-- [start:list-sessions]
for card <- sessions() do
  val files = card.made.mkString(", ")
  println(s"${card.session.id}  ${card.status}  ${card.goal}  read ${card.read}, made [$files]")
// --8<-- [end:list-sessions]

// --8<-- [start:tell-and-observe]
println(tell(ref, "Also write the file bye.txt with the text bye."))
val answer = observe(ref, "Which files did the session write, and did a cell fail?")
println(answer)
// --8<-- [end:tell-and-observe]

// --8<-- [start:start-worktree]
val fix = start(
  "Fix the typo in README.md, and commit the change. Then submit the commit hash.",
  Scope.Worktree("", "fix-readme-typo", "/path/to/repo", "main"),
  StrategyName.Window
)
println(s"Started the session ${fix.id}.")
// --8<-- [end:start-worktree]

// --8<-- [start:start-with-loop]
// The person turned on the goal loop of the controller with /goal on 40, so the controller can give 12 turns.
val review = start(
  "Review the module harness-kernel for bugs. Write each finding to findings.md. Then submit the number of findings.",
  Scope.Directory("/path/to/repo"),
  StrategyName.MemoryTree,
  loop = 12
)
println(s"Started the session ${review.id} with a goal loop of 12 turns.")
// --8<-- [end:start-with-loop]
