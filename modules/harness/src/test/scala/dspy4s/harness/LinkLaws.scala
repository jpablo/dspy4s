package dspy4s.harness

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.Models.*
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The laws of the link from the journal of an `rlm` child to its parent (CODE_HARNESS_DESIGN.md, 8.2, phase 7). */
class LinkLaws extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Runs a harness call with `model`, and gives the id and the journal of each session. */
  private def journalsOf(model: ScriptLm): Vector[(SessionId, Vector[Logged])] =
    val place = Place()
    val kept  = ConcurrentLinkedQueue[(SessionId, Vector[Logged])]()
    try
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.none,
        journals = (id, log) => kept.add((id, log)): Unit
      )
      withModel(model)(Harness.forTask(signature, env, HarnessLimits(steps = 3))(Question("FAKE"))): Unit
      kept.asScala.toVector
    finally place.close()

  private def owner(log: Vector[Logged]): Option[Owner] =
    log.collectFirst { case Logged(_, _, Entry.Activated(_, config)) => config.owner }

  test("Link: the task of an rlm child has the source Parent with the id of the parent and the id of its rlm effect") {
    val model = ScriptLm(
      policy("delegate", Some("""submit(rlm[String]("FAKE-CHILD-TASK"))""")),
      policy("give", Some("""submit("FAKE-VALUE")"""))
    )
    val all           = journalsOf(model)
    val (parent, top) = all.find((_, log) => owner(log).contains(Owner.Optimizer)).getOrElse(fail("no top journal"))
    val (_, child)    = all.find((_, log) => owner(log).contains(Owner.Cell)).getOrElse(fail("no child journal"))
    val effect        = top.collectFirst { case Logged(_, _, Entry.EffectStarted(id, "rlm", _)) => id }
    val source        = child.collectFirst { case Logged(_, meta, Entry.Input(_, _, _)) => meta.source }
    assertEquals(source, effect.map(Source.Parent(parent, _)))
  }

  test("Link: env.journals gets a different id for each session of a call") {
    val model = ScriptLm(
      policy("delegate", Some("""submit(rlm[String]("FAKE-ONE") + rlm[String]("FAKE-TWO"))""")),
      policy("give", Some("""submit("a")""")),
      policy("give", Some("""submit("b")"""))
    )
    val ids = journalsOf(model).map(_._1)
    assertEquals(ids.size, 3)
    assertEquals(ids.distinct.size, 3)
  }
