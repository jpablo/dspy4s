package dspy.signatures

final case class Field(
    name: String,
    description: String,
    kind: String = "str"
)

final case class Signature(
    inputs: List[Field],
    outputs: List[Field],
    instructions: Option[String] = None
)

object Signature {
  def outputNames(sig: Signature): List[String] = sig.outputs.map(_.name)
}
