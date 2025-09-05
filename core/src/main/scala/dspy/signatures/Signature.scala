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

  def parse(spec: String, instructions: Option[String] = None): Signature = {
    val parts = spec.split("->", 2).map(_.trim)
    require(parts.length == 2, s"Invalid signature spec, expected 'inputs -> outputs': $spec")
    def fields(side: String): List[Field] =
      if (side.isEmpty) Nil
      else
        side
          .split(',')
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(n => Field(n, description = "", kind = "str"))
          .toList

    Signature(inputs = fields(parts(0)), outputs = fields(parts(1)), instructions = instructions)
  }
}
