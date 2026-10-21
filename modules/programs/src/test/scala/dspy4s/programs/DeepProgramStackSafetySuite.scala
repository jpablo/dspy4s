package dspy4s.programs

import dspy4s.algebra.AnyObject
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.algebra.{Program, SomeProgram}
import dspy4s.programs.compose.Compose
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.*
import munit.FunSuite

final class DeepProgramStackSafetySuite extends FunSuite:
  import DeepProgramStackSafetySuite.*

  private given RuntimeContext = RuntimeEnvironment.current

  private def chain(size: Int): SomeProgram[Int, Int] =
    val category = Program.erasedCategory
    import category.*

    val increment: SomeProgram[Int, Int] = Program.of(Compose.lift[Int, Int](_ + 1))
    var result                           = category.id[Int](using summon[AnyObject[Int]])
    var index                            = 0
    while index < size do
      result = result >>> increment
      index += 1
    result

  /** `((step 0 >>> step 1) >>> step 2) >>> ...`: the syntax tree is deep on the `first` side. */
  private def leftNestedSteps(size: Int): SomeProgram[Int, Int] =
    val category = Program.erasedCategory
    import category.*

    var result = step(0)
    var index  = 1
    while index < size do
      result = result >>> step(index)
      index += 1
    result

  /** `step 0 >>> (step 1 >>> (step 2 >>> ...))`: the syntax tree is deep on the `second` side. */
  private def rightNestedSteps(size: Int): SomeProgram[Int, Int] =
    val category = Program.erasedCategory
    import category.*

    var result = step(size - 1)
    var index  = size - 2
    while index >= 0 do
      result = step(index) >>> result
      index -= 1
    result

  private def updates(size: Int): Vector[OptimizableParameters] =
    Vector.tabulate(size)(index => OptimizableParameters(instructions = Some(s"update $index")))

  /** The value that a chain of steps produces when the steps hold `parameters` in execution order. */
  private def expectedDigest(parameters: Vector[OptimizableParameters]): Int =
    parameters.foldLeft(0)(Step.digest)

  test("20,000 sequential programs execute without using the JVM call stack") {
    val deep = chain(20_000)
    assertEquals(deep(ProgramCall(0)).map(_.output), Right(20_000))
  }

  test("20,000 sequential programs support inspect and replace without using the JVM call stack") {
    val deep      = chain(20_000)
    val structure = deep.optimizableParameters

    assertEquals(structure.inspect(deep.program), Vector.empty)
    assertEquals(structure.inspectNamed(deep.program), Vector.empty)

    val replaced = structure.replace(deep.program, Vector.empty)
    assertEquals(structure.inspect(replaced), Vector.empty)
  }

  for (association, build) <- associations do
    test(s"20,000 $association leaves receive their updates in execution order") {
      val size      = 20_000
      val deep      = build(size)
      val structure = deep.optimizableParameters
      val next      = updates(size)

      assertEquals(structure.arity(deep.program), size)
      assertEquals(
        deep(ProgramCall(0, traceEnabled = false)).map(_.output),
        Right(expectedDigest(Vector.tabulate(size)(initialParameters)))
      )

      // Each step folds its own parameters into the output, so the result depends on the position of every update.
      val replaced = structure.replace(deep.program, next)
      assertEquals(replaced(ProgramCall(0, traceEnabled = false)).map(_.output), Right(expectedDigest(next)))
    }

    test(s"$association leaves read back the replaced parameters in order") {
      val size      = 300
      val program   = build(size)
      val structure = program.optimizableParameters
      val next      = updates(size)

      assertEquals(structure.read(program.program), Vector.tabulate(size)(initialParameters))

      val replaced = structure.replace(program.program, next)
      assertEquals(structure.read(replaced), next)
      assertEquals(structure.inspectNamed(replaced).map(_._2.parameters), next)
    }

    test(s"$association leaves reject an update vector of the wrong size") {
      val program   = build(5)
      val structure = program.optimizableParameters

      val tooFew  = intercept[IllegalArgumentException](structure.replace(program.program, updates(4)))
      val tooMany = intercept[IllegalArgumentException](structure.replace(program.program, updates(6)))
      assert(tooFew.getMessage.contains("AndThen expects 5 updates, got 4"), tooFew.getMessage)
      assert(tooMany.getMessage.contains("AndThen expects 5 updates, got 6"), tooMany.getMessage)
    }

  test("sequential leaves take their names from the syntax tree") {
    val leftNested  = leftNestedSteps(4)
    val rightNested = rightNestedSteps(4)

    assertEquals(
      leftNested.optimizableParameters.inspectNamed(leftNested.program).map(_._1),
      Vector("first.first.first", "first.first.second", "first.second", "second")
    )
    assertEquals(
      rightNested.optimizableParameters.inspectNamed(rightNested.program).map(_._1),
      Vector("first", "second.first", "second.second.first", "second.second.second")
    )
  }

  test("a failure in a deep chain stops the execution at that step") {
    val category = Program.erasedCategory
    import category.*

    var executedAfterFailure           = 0
    val failure: SomeProgram[Int, Int] = Program.of(Compose.liftEither[Int, Int](_ => Left(StepFailure)))
    val counted: SomeProgram[Int, Int] = Program.of(Compose.lift[Int, Int] { value =>
      executedAfterFailure += 1
      value
    })
    val program: SomeProgram[Int, Int] = (chain(1_000) >>> failure) >>> (counted >>> chain(1_000))

    assertEquals(program(ProgramCall(0)).map(_.output), Left(StepFailure))
    assertEquals(executedAfterFailure, 0)
  }

  private def associations: List[(String, Int => SomeProgram[Int, Int])] =
    List("left-nested" -> leftNestedSteps, "right-nested" -> rightNestedSteps)

object DeepProgramStackSafetySuite:
  private val layout = SignatureLayout.parse("input -> output").toOption.get

  private val StepFailure: DspyError = dspy4s.core.contracts.ValidationError("step failed")

  private def initialParameters(index: Int): OptimizableParameters =
    OptimizableParameters(instructions = Some(s"initial $index"))

  private def step(index: Int): SomeProgram[Int, Int] =
    Program.of(Step(initialParameters(index)))

  /** A program stub with one learnable leaf. The output depends on the parameters and on the input, so a chain of steps
    * reports the order of its parameters.
    */
  private final case class Step(parameters: OptimizableParameters) extends Module[Int, Int]:
    override val moduleName: String                                                                                  = "step"
    override protected val lifecycle: ModuleLifecycle[Int, Int]                                                      = ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      Right(Prediction(Step.digest(call.input, parameters), RawPrediction.empty))

  private object Step:
    def digest(previous: Int, parameters: OptimizableParameters): Int =
      previous * 31 + parameters.instructions.hashCode

    given stepOptimizable: OptimizableLeaf[Step] with
      def get(program                        : Step): OptimizableParameters                = program.parameters
      def metadata(@annotation.unused program: Step): OptimizableMetadata                  = OptimizableMetadata.from(layout, "step")
      def set(program                        : Step, updated: OptimizableParameters): Step = program.copy(parameters = updated)
