package dspy4s.core

import dspy4s.core.contracts.{ExchangeSinkRef, RuntimeContext, RuntimeServices}
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

/** The exchange sink of a context (docs/code-harness/OBSERVABILITY.md, 4.1): each operation of the context keeps it, as
  * it keeps the other services.
  */
class ExchangeSinkContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private object First  extends ExchangeSinkRef
  private object Second extends ExchangeSinkRef

  test("Exchange: the flat copy of a context keeps its exchange sink") {
    val copied = RuntimeContext(exchangeSink = Some(First)).copy(adapter = None)
    assert(copied.exchangeSink.exists(_ eq First), copied.toString)
  }

  test("Exchange: fillFrom takes the exchange sink of the defaults, and a set sink wins") {
    val defaults = RuntimeContext(exchangeSink = Some(First))
    assert(RuntimeContext().fillFrom(defaults).exchangeSink.exists(_ eq First))
    assert(RuntimeContext(exchangeSink = Some(Second)).fillFrom(defaults).exchangeSink.exists(_ eq Second))
  }

  test("Exchange: keptBy compares the exchange sink by reference") {
    assert(RuntimeServices(exchangeSink = Some(First)).keptBy(RuntimeServices(exchangeSink = Some(First))))
    assert(!RuntimeServices(exchangeSink = Some(First)).keptBy(RuntimeServices(exchangeSink = Some(Second))))
    assert(!RuntimeServices(exchangeSink = Some(First)).keptBy(RuntimeServices()))
  }

  test("Exchange: the context of a thread keeps its exchange sink") {
    val seen = RuntimeEnvironment.withContext(RuntimeContext(exchangeSink = Some(First)))(RuntimeEnvironment.current)
    assert(seen.exchangeSink.exists(_ eq First), seen.toString)
  }
