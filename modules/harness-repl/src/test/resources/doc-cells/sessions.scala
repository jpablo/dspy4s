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
tell(ref, "Also write the file bye.txt with the text bye.")
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
