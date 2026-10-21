package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.ForwardingLanguageModel
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.runtime.ManagedLanguageModel
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/** A wrapper that extends [[ForwardingLanguageModel]] gives each member of its delegate that has a default in
  * [[LanguageModel]]: the capability flags, the default options, the cache identity, and `close`.
  */
class ForwardingLanguageModelSuite extends FunSuite:

  /** A model that changes each member that has a default, and counts its closes. */
  private final class Capable(closes: AtomicInteger) extends LanguageModel:
    override val id: String                          = "capable"
    override val mode: LmMode                        = LmMode.Chat
    override def supportsFunctionCalling: Boolean    = true
    override def supportsResponseSchema: Boolean     = true
    override def supportsReasoning: Boolean          = true
    override def supportsDecisionRequests: Boolean   = true
    override def defaultOptions: DynamicValue.Record = DynamicValues.record("temperature" := 0.5)
    override def cacheIdentity: DynamicValue.Record  = DynamicValues.record("base_url" := "https://a.test")

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "not used"))

    override def close(): Unit =
      val _ = closes.incrementAndGet()

  /** The smallest wrapper: it defines only the members that have no default. */
  private final class Plain(val delegate: LanguageModel) extends ForwardingLanguageModel:
    override val id: String   = "plain"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      delegate.call(request)

  /** A model with only the members that have no default. */
  private object Bare extends LanguageModel:
    override val id: String   = "bare"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "not used"))

  private type Members = (Boolean, Boolean, Boolean, Boolean, DynamicValue.Record, DynamicValue.Record)

  private def members(model: LanguageModel): Members = (
    model.supportsFunctionCalling,
    model.supportsResponseSchema,
    model.supportsReasoning,
    model.supportsDecisionRequests,
    model.defaultOptions,
    model.cacheIdentity
  )

  /** The name and the parameter types of `method`. */
  private def signature(method: Method): String =
    s"${method.getName}(${method.getParameterTypes.map(_.getName).mkString(",")})"

  test("a bare model has no capability, no default option, and no cache identity") {
    assertEquals(
      members(Bare),
      (false, false, false, false, DynamicValue.Record.empty, DynamicValue.Record.empty)
    )
  }

  test("a forwarding wrapper gives each member of its delegate that has a default, and closes the delegate") {
    val closes   = AtomicInteger(0)
    val delegate = Capable(closes)
    val wrapper  = Plain(delegate)
    assertEquals(members(wrapper), members(delegate))
    wrapper.close()
    assertEquals(closes.get(), 1)
  }

  test("a managed model is a forwarding wrapper") {
    val closes                           = AtomicInteger(0)
    val managed: ForwardingLanguageModel = ManagedLanguageModel(Capable(closes))
    assertEquals(members(managed), members(Capable(closes)))
    managed.close()
    assertEquals(closes.get(), 1)
  }

  test("the forwarding wrapper forwards each member of LanguageModel that has a default, except the async calls") {
    // The async calls run `call` of the wrapper, so the rules of the wrapper (for example its cache) apply to them.
    // A member that a later version adds to LanguageModel with a default makes this test fail until the trait
    // forwards it, or until this list names it with a reason.
    val throughCall = Set("acall", "acallExecuted")
    val defaults    = classOf[LanguageModel].getMethods.toVector
      .filter(_.isDefault)
      .filterNot(method => throughCall.contains(method.getName))
      .map(signature)
      .toSet
    val forwarded =
      classOf[ForwardingLanguageModel].getDeclaredMethods.toVector.filter(_.isDefault).map(signature).toSet
    // The check must see the members that exist now, else the reflection reads nothing.
    val known = Set(
      "supportsFunctionCalling()",
      "supportsResponseSchema()",
      "supportsReasoning()",
      "supportsDecisionRequests()",
      "defaultOptions()",
      "cacheIdentity()",
      "close()"
    )
    assert(known.subsetOf(defaults), defaults.toString)
    assertEquals(defaults -- forwarded, Set.empty[String])
  }
