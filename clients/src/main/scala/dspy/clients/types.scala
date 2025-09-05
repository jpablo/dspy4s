package dspy.clients

final case class Prompt(content: String)

final case class Completion(
    text: String,
    raw: ujson.Value
)
