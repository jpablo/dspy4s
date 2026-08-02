package dspy4s.core

import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Executed
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.LmUsage
import dspy4s.core.algebra.Monoid
import dspy4s.core.contracts.RuntimeConfig
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeDelta
import dspy4s.core.contracts.RuntimeScope
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import org.scalacheck.Gen
import org.scalacheck.Prop
import zio.blocks.schema.DynamicValue

import java.time.Instant

/** Executable laws for the runtime-output and usage algebras introduced by the runtime partitioning. */
class ExecutionAlgebraLawSuite extends munit.ScalaCheckSuite:

  private def trace(name: String): TraceEntry =
    TraceEntry(name, DynamicValue.Record.empty, DynamicValue.Record.empty, timestamp = Instant.EPOCH)

  private def history(name: String): HistoryEntry =
    HistoryEntry(name, DynamicValue.Record.empty, timestamp = Instant.EPOCH)

  private val genDelta: Gen[RuntimeDelta] =
    for
      traces  <- Gen.listOf(Gen.alphaStr)
      history <- Gen.listOf(Gen.alphaStr)
    yield RuntimeDelta(traces.toVector.map(trace), history.toVector.map(ExecutionAlgebraLawSuite.this.history))

  private val genCategory: Gen[TokenCategory] = Gen.oneOf(
    TokenCategory.Cached,
    TokenCategory.Audio,
    TokenCategory.Reasoning,
    TokenCategory.AcceptedPrediction,
    TokenCategory.RejectedPrediction,
    TokenCategory.Other("provider_counter")
  )

  private val genUsage: Gen[LmUsage] =
    for
      total      <- Gen.choose(0L, 100000L)
      prompt     <- Gen.choose(0L, 100000L)
      completion <- Gen.choose(0L, 100000L)
      extras     <- Gen.mapOf(Gen.zip(genCategory, Gen.choose(0L, 100000L)))
    yield LmUsage(total, prompt, completion, extras)

  property("RuntimeDelta is an ordered monoid") {
    val monoid = Monoid[RuntimeDelta]
    Prop.forAll(genDelta, genDelta, genDelta) { (a, b, c) =>
      val associativity = monoid.associativity(a, b, c)
      val leftIdentity  = monoid.identityLeft(a)
      val rightIdentity = monoid.identityRight(a)
      associativity.lhs == associativity.rhs &&
      leftIdentity.lhs == leftIdentity.rhs &&
      rightIdentity.lhs == rightIdentity.rhs
    }
  }

  property("LmUsage is a pointwise commutative monoid") {
    val monoid = Monoid[LmUsage]
    Prop.forAll(genUsage, genUsage, genUsage) { (a, b, c) =>
      val associativity = monoid.associativity(a, b, c)
      val leftIdentity  = monoid.identityLeft(a)
      val rightIdentity = monoid.identityRight(a)
      associativity.lhs == associativity.rhs &&
      leftIdentity.lhs == leftIdentity.rhs &&
      rightIdentity.lhs == rightIdentity.rhs &&
      a.combine(b) == b.combine(a)
    }
  }

  test("RuntimeDelta combination preserves trace and history order") {
    val left   = RuntimeDelta(Vector(trace("a")), Vector(history("1")))
    val right  = RuntimeDelta(Vector(trace("b")), Vector(history("2")))
    val result = left.combine(right)

    assertEquals(result.trace.map(_.component), Vector("a", "b"))
    assertEquals(result.history.map(_.component), Vector("1", "2"))
  }

  test("Executed map preserves output and flatMap combines output in execution order") {
    val first   = RuntimeDelta(Vector(trace("first")))
    val next    = RuntimeDelta(Vector(trace("next")))
    val initial = Executed(1, first)

    assertEquals(initial.map(_ + 1), Executed(2, first))
    assertEquals(initial.flatMap(value => Executed(value + 1, next)), Executed(2, first.combine(next)))
  }

  property("Executed obeys the writer flatMap identity and associativity laws") {
    Prop.forAll(Gen.choose(-1000, 1000), genDelta) { (value, delta) =>
      val executed                 = Executed(value, delta)
      def f(n: Int): Executed[Int] = Executed(n + 1, RuntimeDelta(Vector(trace("f"))))
      def g(n: Int): Executed[Int] = Executed(n * 2, RuntimeDelta(Vector(trace("g"))))

      Executed.pure(value).flatMap(f) == f(value) &&
      executed.flatMap(Executed.pure) == executed &&
      executed.flatMap(f).flatMap(g) == executed.flatMap(n => f(n).flatMap(g))
    }
  }

  test("RuntimeContext partitions round-trip through the flat compatibility surface") {
    val metadata = DynamicValues.record("run" := "abc")
    val delta    = RuntimeDelta(Vector(trace("step")), Vector(history("lm")))
    val context = RuntimeContext(
      numThreads = Some(ThreadCount(4)),
      callbackMetadata = metadata,
      captureFailureTraces = true,
      asyncTaskId = Some("task"),
      trace = delta.trace,
      history = delta.history
    )

    assertEquals(
      context.config,
      RuntimeConfig(numThreads = Some(ThreadCount(4)), callbackMetadata = metadata, captureFailureTraces = true)
    )
    assertEquals(context.scope, RuntimeScope(asyncTaskId = Some("task")))
    assertEquals(context.delta, delta)
    assertEquals(context.copy(numThreads = Some(ThreadCount(8))).config.numThreads, Some(ThreadCount(8)))
  }

  test("fillFrom inherits environment inputs but never an accumulated delta") {
    val defaults = RuntimeContext(
      numThreads = Some(ThreadCount(8)),
      asyncTaskId = Some("global-task"),
      trace = Vector(trace("global")),
      history = Vector(history("global"))
    )
    val local  = RuntimeContext(trace = Vector(trace("local")))
    val filled = local.fillFrom(defaults)

    assertEquals(filled.numThreads, Some(ThreadCount(8)))
    assertEquals(filled.asyncTaskId, Some("global-task"))
    assertEquals(filled.trace.map(_.component), Vector("local"))
    assertEquals(filled.history, Vector.empty[HistoryEntry])
  }

  test("RawPrediction preserves typed provider usage without string conversion") {
    val usage = LmUsage(
      totalTokens = 9,
      promptTokens = 4,
      completionTokens = 5,
      extras = Map(TokenCategory.Cached -> 2L, TokenCategory.Other("vendor_x") -> 3L)
    )

    assertEquals(RawPrediction.empty.withUsage(usage).lmUsage, Some(usage))
  }
