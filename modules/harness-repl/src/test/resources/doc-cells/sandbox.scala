// mode: work
// The cells of site/docs/harness/sandbox.md. DocCellLaws runs them in order, in one REPL.

// --8<-- [start:git-status]
val status = exec("git status --short --branch")
println(status)
println(exec("git log --oneline -3"))
// --8<-- [end:git-status]

// --8<-- [start:commit-on-branch]
val branch = "demo"
println(exec(s"git switch -c $branch"))
println(exec("git add notes.txt && git commit -m 'Add the notes'"))
// --8<-- [end:commit-on-branch]

// --8<-- [start:repo-in-temp]
val init = """cd "$TMPDIR" && git init -q demo && git -C demo commit -q --allow-empty -m start"""
println(exec(init))
println(exec("""git -C "$TMPDIR/demo" log --oneline"""))
// --8<-- [end:repo-in-temp]

// The work mode has no Network, so httpGet does not compile.
// expect: compile-error
// --8<-- [start:no-network]
val url  = "https://api.example.com/v1/models"
val page = httpGet(url)
println(page.take(200))
// --8<-- [end:no-network]
