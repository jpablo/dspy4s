package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ValidationError
import dspy4s.core.data.Example
import dspy4s.programs.algebra.Program
import dspy4s.programs.optimization.OptimizableTraversal
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue

/** A runtime-parsed signature whose input and output types are FRESH abstract types minted by the parse,
  * restoring one-decoder-per-type for the dynamic layer.
  *
  * The problem this solves: every `Signature.fromStringDynamic` program shares the input type
  * `DynamicValue.Record`, while each needs its own field-validating decoder, so the type cannot determine the
  * decoder and the parameterized category's left unit holds only under the `ProgramInput` coherence law. The bundle
  * makes the fibration honest in the type system: each parse mints its own `In` / `Out` as abstract type
  * members (fresh per stable path, the path-dependent freshness the compiler enforces), and the codec and the
  * signature are born from the same parse behind that abstraction. Outside, the only source of a
  * `RecordCodec[s.In]` is `s.inputCodec` and the only source of a `Signature[s.In, _]` is `s.signature`, so a
  * signature from one parse cannot be recombined with a codec from another: identity and any program over the
  * bundle decode identically as a consequence of abstraction, not as an instance obligation. `RecordCodec` is sealed,
  * so application code cannot introduce a competing decoder for either fresh type.
  *
  * Scala widens an ordinary `val alias = s` back to `DynamicSignature`, which gives the alias a new path projection.
  * Use [[stable]] when a second binding must retain this bundle's exact `In` / `Out` types. Re-parsing the same string
  * still mints a distinct object (two fibers that happen to agree).
  *
  * Cardinality-shaped value dependence uses the same boundary pattern separately: `MultiChainComparison`
  * validates a vector against `m`, then hides it behind its own path-branded carrier before prediction.
  *
  * Status: the recommended user path for runtime-string signatures. `s.predict()` builds the typed program;
  * composition, optimization (`OptimizableTraversal` + `ProgramRunner` over a packaged `Program`), and cross-fiber
  * pipelines (via [[DynamicSignature.bridge]]) all run through the same machinery as statically typed
  * programs. `Signature.fromStringDynamic` remains the substrate, and the plain data-bag surface for
  * consumers that never enter the category (optimizer helper generations, the evaluation judge).
  */
sealed trait DynamicSignature:
  /** The fresh input type: externally abstract, so this bundle's codec and signature are its only sources. */
  type In

  /** The fresh output type (codec-equipped so mid-pipeline objects support identity too). */
  type Out

  val signature: Signature[In, Out]

  /** The canonical decoder for [[In]]: definitionally the signature's own input decode. */
  given inputCodec: RecordCodec[In]

  /** The canonical decoder for [[Out]]: definitionally the signature's own output decode. */
  given outputCodec: RecordCodec[Out]

  /** Project this path-dependent bundle into an ordinary generic value. Unlike a direct alias, the generic type
    * arguments survive Scala's value widening: `val same = signature.stable` gives
    * `same.In = signature.In` and likewise for `Out`.
    */
  final def stable: DynamicSignature.Stable[In, Out] = new DynamicSignature.Stable(this)

  /** Validating entry: decode a raw record into the tagged input (field presence checked here, at the
    * boundary, rather than at call time). */
  final def input(record: DynamicValue.Record): Either[DspyError, In] =
    signature.inputShape.decode(record)

  /** Build a typed [[Predict]] over this bundle's signature. A path-dependent constructor, so `In` / `Out`
    * line up without threading `signature` at call sites: `val p = s.predict()` is the runtime-string
    * counterpart of `Predict(Signature.derived[Q, A](...))`. Outputs are read from the prediction's `raw`
    * envelope (the wire record), the same surface the dynamic layer always exposed. */
  final def predict(
      demos: Vector[Example] = Vector.empty,
      name: Option[String] = None,
      config: DynamicValue.Record = DynamicValue.Record.empty
  ): Predict[In, Out] =
    Predict(signature, demos = demos, name = name, config = config)

  /** Package this bundle's predict as a parameterized-category object in one step: the object codec comes from the
    * bundle itself, so no `import s.given` is needed at the call site. */
  final def packaged(
      demos: Vector[Example] = Vector.empty,
      name: Option[String] = None,
      config: DynamicValue.Record = DynamicValue.Record.empty
  ): Program[In, Out] =
    Program.of(predict(demos, name, config))(using summon[OptimizableTraversal[Predict[In, Out]]], inputCodec)

object DynamicSignature:

  /** Alias-safe generic view of a dynamic-signature bundle. Its type arguments capture the originating path once, then
    * survive any number of ordinary `val` aliases.
    */
  final class Stable[I, O] private[DynamicSignature] (
      underlying: DynamicSignature { type In = I; type Out = O }
  ):
    type In  = I
    type Out = O

    val signature: Signature[I, O] = underlying.signature
    given inputCodec: RecordCodec[I] = underlying.inputCodec
    given outputCodec: RecordCodec[O] = underlying.outputCodec

    def input(record: DynamicValue.Record): Either[DspyError, I] = underlying.input(record)

    def predict(
        demos: Vector[Example] = Vector.empty,
        name: Option[String] = None,
        config: DynamicValue.Record = DynamicValue.Record.empty
    ): Predict[I, O] = underlying.predict(demos, name, config)

    def packaged(
        demos: Vector[Example] = Vector.empty,
        name: Option[String] = None,
        config: DynamicValue.Record = DynamicValue.Record.empty
    ): Program[I, O] = underlying.packaged(demos, name, config)

  /** Parse a DSPy-style DSL string at runtime, minting a fresh pair of input/output types for it. The declared
    * `DynamicSignature` return type is what seals the type members: the concrete representation (`In` and
    * `Out` are both `DynamicValue.Record` underneath) never escapes. */
  def parse(dsl: String, instructions: String = ""): Either[DspyError, DynamicSignature] =
    Signature.fromStringDynamic(dsl, instructions).map { parsed =>
      new DynamicSignature:
        type In  = DynamicValue.Record
        type Out = DynamicValue.Record
        val signature: Signature[In, Out]   = parsed
        given inputCodec: RecordCodec[In]   = RecordCodec.fromShape(parsed.inputShape)
        given outputCodec: RecordCodec[Out] = RecordCodec.fromShape(parsed.outputShape)
    }

  /** The reindexing morphism across fibers: a parameter-free program converting one bundle's outputs into
    * another's inputs by factoring through the wire (encode, then the target's validating entry). Distinct
    * parses mint distinct types, so direct cross-bundle composition is a compile error by design; a bridge is
    * the only crossing, and it is failable where the direct composition was silently wrong.
    *
    * Fails EAGERLY when the target's input fields are not covered by the source's output fields; that
    * name-set condition is the base-level compatibility arrow this bridge lifts. At run time the validating
    * entry rejects records whose declared fields are absent. Parameter-free ([[LiftEither]]), so it
    * contributes nothing to `params` and pipelines optimize exactly as before. */
  def bridge(from: DynamicSignature, to: DynamicSignature): Either[DspyError, Program[from.Out, to.In]] =
    val provided = from.signature.layout.outputFields.map(_.name).toSet
    val missing  = to.signature.layout.inputFields.map(_.name).filterNot(provided.contains)
    if missing.nonEmpty then
      Left(ValidationError(
        s"bridge: target inputs not covered by source outputs; missing: ${missing.mkString(", ")}"
      ))
    else
      import from.outputCodec
      Right(Program.of(LiftEither[from.Out, to.In](value => to.input(from.signature.outputShape.encode(value)))))

  // Note: `bridge` packages at the SOURCE bundle's output object (`import from.outputCodec` supplies the
  // object codec `Program.of` now requires); the target side needs no codec, since only domains are objects
  // a package is created at.
