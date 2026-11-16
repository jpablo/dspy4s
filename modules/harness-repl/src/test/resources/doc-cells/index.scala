// mode: work
// The cells of site/docs/harness/index.md. DocCellLaws runs them in order, in one REPL.

// --8<-- [start:first-cell]
val readme = read("README.md")
val lines  = readme.linesIterator.size
println(s"README.md has $lines lines.")
// --8<-- [end:first-cell]
