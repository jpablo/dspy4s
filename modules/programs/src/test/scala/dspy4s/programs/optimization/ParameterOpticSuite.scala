package dspy4s.programs.optimization

import dspy4s.algebra.Lens
import dspy4s.algebra.Optic.*
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

final class ParameterOpticSuite extends FunSuite:

  private final case class Leaf(parameters: OptimizableParameters)
  private final case class PairProgram(first: Leaf, second: Leaf, fixedLabel: String)

  private val layout = SignatureDsl.parse("input -> output").toOption.get

  private def view(parameters: OptimizableParameters): OptimizableView =
    OptimizableView(OptimizableMetadata.from(layout, "leaf"), parameters)

  private val leafOptic: ParameterOptic[Leaf, 1] =
    ParameterOptic.leaf("self", leaf => view(leaf.parameters), (leaf, parameters) => leaf.copy(parameters = parameters))

  private val children = new Lens[PairProgram, (Leaf, Leaf)]:
    def get(program: PairProgram): (Leaf, Leaf)                           = program.first -> program.second
    def set(program: PairProgram, replacement: (Leaf, Leaf)): PairProgram =
      program.copy(first = replacement._1, second = replacement._2)

  private val pairOptic: ParameterOptic[PairProgram, 2] =
    ParameterOptic.pairThrough(children, leafOptic, leafOptic, Some("first"), Some("second"))

  private val pairStructure: OptimizableStructure.Of[PairProgram, 2] =
    ParameterOptic.toStructure("PairProgram", pairOptic)

  private val namedLeafOptic: ParameterOptic[Leaf, 1] = ParameterOptic.leaf(
    "value",
    leaf => view(leaf.parameters),
    (leaf, parameters) => leaf.copy(parameters = parameters)
  )

  private val namedLeafStructure: OptimizableStructure.Of[Leaf, 1] =
    ParameterOptic.toStructure("NamedLeaf", namedLeafOptic)

  private val stackSafeNamedPair: OptimizableStructure.Of[PairProgram, 2] =
    ParameterOptic.pairStructure[PairProgram, Leaf, Leaf, 1, 1](
      "PairProgram",
      _.first,
      _.second,
      (whole, first, second) => whole.copy(first = first, second = second),
      namedLeafStructure,
      namedLeafStructure,
      Some("first"),
      Some("second")
    )

  private val first   = OptimizableParameters(instructions = Some("first"), config = DynamicValues.record())
  private val second  = OptimizableParameters(instructions = Some("second"), config = DynamicValues.record())
  private val program = PairProgram(Leaf(first), Leaf(second), fixedLabel = "keep")

  test("a Lens composes with a multi-focus parameter optic through the carrier bridge") {
    val opened = pairOptic.to(program)

    assertEquals(opened.values.map(_.displayName), Vector("first", "second"))
    assertEquals(opened.values.map(_.view.parameters), Vector(first, second))

    val updatedSecond = second.copy(instructions = Some("updated"))
    val rebuilt       = pairOptic.from(ParameterCarrier(opened.context, Vector(first, updatedSecond)))
    assertEquals(rebuilt, PairProgram(Leaf(first), Leaf(updatedSecond), fixedLabel = "keep"))
  }

  test("focus mapping performs get-put through the generic carrier capability") {
    val rebuilt = pairOptic.modify(program)(_.view.parameters)
    assertEquals(rebuilt, program)
  }

  test("the public structure retains exact arity and derives inspection from the optic") {
    val exact: OptimizableStructure.WithArity[PairProgram, 2] = pairStructure
    val updatedFirst                                          = first.copy(instructions = Some("updated first"))

    assertEquals(exact.arity(program), 2)
    assertEquals(exact.inspectNamed(program).map(_._1), Vector("first", "second"))
    assertEquals(
      exact.replace(program, Vector(updatedFirst, second)),
      PairProgram(Leaf(updatedFirst), Leaf(second), fixedLabel = "keep")
    )
  }

  test("nested non-self names retain both path segments") {
    val namedPair = ParameterOptic.pairThrough(children, namedLeafOptic, namedLeafOptic, Some("first"), Some("second"))

    assertEquals(namedPair.to(program).values.map(_.displayName), Vector("first.value", "second.value"))
    assertEquals(stackSafeNamedPair.inspectNamed(program).map(_._1), Vector("first.value", "second.value"))
  }

  test("a pair accepts an empty right focus at the exact split boundary") {
    val oneFocus = ParameterOptic.pair(leafOptic, ParameterOptic.empty[Unit], Some("leaf"), Some("empty"))
    val opened   = oneFocus.to(Leaf(first) -> ())
    val rebuilt  = oneFocus.from(ParameterCarrier(opened.context, Vector(second)))

    assertEquals(rebuilt, Leaf(second) -> ())
  }

  test("composed context rejects extra replacement foci after rebuilding its inner optic") {
    val opened = pairOptic.to(program)
    val error  = intercept[IllegalArgumentException] {
      pairOptic.from(ParameterCarrier(opened.context, Vector(first, second, first)))
    }

    assert(error.getMessage.contains("context consumes 2 replacement foci"))
  }
