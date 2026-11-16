// mode: local

// --8<-- [start:zoom-a-message]
// The view shows only the start of the message [0.0]. Read the whole text.
val first = zoom("0.0")
println(s"${first.length} characters")
println(first)
// --8<-- [end:zoom-a-message]

// --8<-- [start:zoom-down]
// The line [1.0] covers the messages 0 and 1. Read its two halves first.
println(zoom("1.0"))
// Then search the whole text of one message for the fact.
val second = zoom("0.1")
val hits   = second.linesIterator.filter(_.contains("port")).toVector
println(if hits.isEmpty then "no line with port" else hits.mkString("\n"))
// --8<-- [end:zoom-down]
