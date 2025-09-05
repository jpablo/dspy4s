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

  def inputNames(sig: Signature): List[String] = sig.inputs.map(_.name)

  def missingInputKeys(sig: Signature, provided: Set[String]): List[String] =
    inputNames(sig).filterNot(provided)

  def missingOutputKeys(sig: Signature, present: Set[String]): List[String] =
    outputNames(sig).filterNot(present)
}
