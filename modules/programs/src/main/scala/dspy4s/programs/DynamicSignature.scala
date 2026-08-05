package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ValidationError
import dspy4s.core.data.Example
import dspy4s.programs.algebra.Program
import dspy4s.programs.compose.LiftEither
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue

/** A runtime-parsed signature whose input and output types are FRESH abstract types minted by the parse, restoring
  * one-decoder-per-type for the dynamic layer.
  *
  * The problem this solves: every `Signature.fromStringDynamic` program shares the input type `DynamicValue.Record`,
  * while each needs its own field-validating decoder, so the shared type cannot determine the decoder. The bundle
  * restores that distinction in the type system: each parse mints its own `In` / `Out` as abstract type members (fresh
  * per stable path, the path-dependent freshness the compiler enforces), and the codec and the signature are born from
  * the same parse behind that abstraction. Outside, the only source of a `RecordCodec[s.In]` is `s.inputCodec` and the
  * only source of a `Signature[s.In, _]` is `s.signature`, so a signature from one parse cannot be recombined with a
  * codec from another: every record-boundary execution over the bundle decodes identically as a consequence of
  * abstraction, not as an instance obligation. `RecordCodec` is sealed, so application code cannot introduce a
  * competing decoder for either fresh type.
  *
  * Scala widens an ordinary `val alias = s` back to `DynamicSignature`, which gives the alias a new path projection.
  * Use [[stable]] when a second binding must retain this bundle's exact `In` / `Out` types. Re-parsing the same string
  * still mints a distinct object (two fibers that happen to agree).
  *
  * Cardinality-shaped value dependence uses the same boundary pattern separately: `MultiChainComparison` validates a
  * vector against `m`, then hides it behind its own path-branded carrier before prediction.
  *
  * Status: the recommended user path for runtime-string signatures. `s.predict()` builds the program; composition,
  * optimization (`OptimizableStructure` + `ProgramRunner` over a packaged `Program`), and cross-fiber pipelines (via
  * [[DynamicSignature.bridge]]) all run through the same machinery as domain-valued programs.
  * `Signature.fromStringDynamic` remains the substrate, and the plain data-bag surface for consumers that never enter
  * the category (optimizer helper generations, the evaluation judge).
  */
sealed trait DynamicSignature:
  /** The fresh input type: externally abstract, so this bundle's codec and signature are its only sources. */
  type In

  /** The fresh output type (codec-equipped so it can later serve as a record-boundary input). */
  type Out

  val signature: Signature[In, Out]

  /** The canonical decoder for [[In]]: definitionally the signature's own input decode. */
  given inputCodec: RecordCodec[In]

  /** The canonical decoder for [[Out]]: definitionally the signature's own output decode. */
  given outputCodec: RecordCodec[Out]

  /** Project this path-dependent bundle into an ordinary generic value. Unlike a direct alias, the generic type
    * arguments survive Scala's value widening: `val same = signature.stable` gives `same.In = signature.In` and
    * likewise for `Out`.
    */
  final def stable: DynamicSignature.Stable[In, Out] = new DynamicSignature.Stable(this)

  /** Validating entry: decode a raw record into the tagged input (field presence checked here, at the boundary, rather
    * than at call time).
    */
  final def input(record: DynamicValue.Record): Either[DspyError, In] =
    signature.inputShape.decode(record)

  /** Build a [[Predict]] over this bundle's signature. A path-dependent constructor, so `In` / `Out` line up without
    * threading `signature` at call sites: `val p = s.predict()` is the runtime-string counterpart of
    * `Predict(Signature.derived[Q, A](...))`. Outputs are read from the prediction's `raw` envelope (the wire record),
    * the same surface the dynamic layer always exposed.
    */
  final def predict(
      demos : Vector[Example]     = Vector.empty,
      name  : Option[String]      = None,
      config: DynamicValue.Record = DynamicValue.Record.empty
  ): Predict[In, Out] =
    Predict(signature, demos = demos, name = name, config = config)

  /** Package this bundle's predict as a graded-category morphism in one step. */
  final def packaged(
      demos : Vector[Example]     = Vector.empty,
      name  : Option[String]      = None,
      config: DynamicValue.Record = DynamicValue.Record.empty
  ): Program[In, Out, 1] =
    Program.of(predict(demos, name, config))

object DynamicSignature:

  /** Alias-safe generic view of a dynamic-signature bundle. Its type arguments capture the originating path once, then
    * survive any number of ordinary `val` aliases.
    */
  final class Stable[I, O] private[DynamicSignature] (
      underlying: DynamicSignature { type In = I; type Out = O }
  ):
    type In  = I
    type Out = O

    val signature: Signature[I, O]    = underlying.signature
    given inputCodec: RecordCodec[I]  = underlying.inputCodec
    given outputCodec: RecordCodec[O] = underlying.outputCodec

    def input(record: DynamicValue.Record): Either[DspyError, I] = underlying.input(record)

    def predict(
        demos : Vector[Example]     = Vector.empty,
        name  : Option[String]      = None,
        config: DynamicValue.Record = DynamicValue.Record.empty
    ): Predict[I, O] = underlying.predict(demos, name, config)

    def packaged(
        demos : Vector[Example]     = Vector.empty,
        name  : Option[String]      = None,
        config: DynamicValue.Record = DynamicValue.Record.empty
    ): Program[I, O, 1] = underlying.packaged(demos, name, config)

  /** Parse a DSPy-style DSL string at runtime, minting a fresh pair of input/output types for it. The declared
    * `DynamicSignature` return type is what seals the type members: the concrete representation (`In` and `Out` are
    * both `DynamicValue.Record` underneath) never escapes.
    */
  def parse(dsl: String, instructions: String = ""): Either[DspyError, DynamicSignature] =
    Signature.fromStringDynamic(dsl, instructions).map { parsed =>
      new DynamicSignature:
        type In  = DynamicValue.Record
        type Out = DynamicValue.Record
        val signature: Signature[In, Out]   = parsed
        given inputCodec: RecordCodec[In]   = RecordCodec.fromShape(parsed.inputShape)
        given outputCodec: RecordCodec[Out] = RecordCodec.fromShape(parsed.outputShape)
    }

  /** The reindexing morphism across fibers: a parameter-free program converting one bundle's outputs into another's
    * inputs by factoring through the wire (encode, then the target's validating entry). Distinct parses mint distinct
    * types, so direct cross-bundle composition is a compile error by design; a bridge is the only crossing, and it is
    * failable where the direct composition was silently wrong.
    *
    * Fails EAGERLY when the target's input fields are not covered by the source's output fields; that name-set
    * condition is the base-level compatibility arrow this bridge lifts. At run time the validating entry rejects
    * records whose declared fields are absent. Parameter-free ([[LiftEither]]), so it contributes nothing to `params`
    * and pipelines optimize exactly as before.
    */
  def bridge(from: DynamicSignature, to: DynamicSignature): Either[DspyError, Program[from.Out, to.In, 0]] =
    val provided = from.signature.layout.outputFields.map(_.name).toSet
    val missing  = to.signature.layout.inputFields.map(_.name).filterNot(provided.contains)
    if missing.nonEmpty then
      Left(ValidationError(
        s"bridge: target inputs not covered by source outputs; missing: ${missing.mkString(", ")}"
      ))
    else
      Right(Program.of(LiftEither[from.Out, to.In](value => to.input(from.signature.outputShape.encode(value)))))
