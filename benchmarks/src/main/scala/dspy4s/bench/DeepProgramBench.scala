package dspy4s.bench

import dspy4s.algebra.AnyObject
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.algebra.{Program, SomeProgram}
import dspy4s.programs.compose.Compose
import dspy4s.programs.contracts.{Prediction, ProgramCall}
import dspy4s.programs.optimization.OptimizableView
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Level, Mode, OutputTimeUnit, Param, Scope, Setup, State}

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class DeepProgramBench:
  @Param(Array("10", "1000"))
  var size: Int = 0

  private var program: SomeProgram[Int, Int] = null.asInstanceOf[SomeProgram[Int, Int]]
  private given RuntimeContext               = RuntimeEnvironment.current

  @Setup(Level.Trial)
  def setup(): Unit =
    val category = Program.erasedCategory
    import category.*

    val increment: SomeProgram[Int, Int] = Program.of(Compose.lift[Int, Int](_ + 1))
    var built                            = category.id[Int](using summon[AnyObject[Int]])
    var index                            = 0
    while index < size do
      built = built >>> increment
      index += 1
    program = built

  @Benchmark def execute(): Either[?, Prediction[Int]] =
    program(ProgramCall(0))

  @Benchmark def inspect(): Vector[OptimizableView] =
    val current = program
    current.optimizableParameters.inspect(current.program)
