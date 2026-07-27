package dspy4s.programs.contracts

import zio.blocks.schema.DynamicValue

/** The uniform invocation envelope for every program boundary.
  *
  * `I` is the boundary's input carrier: typed modules use their Scala input type, while the dynamic spine uses
  * `DynamicValue.Record`. [[mapInput]] changes only that carrier and preserves every execution control, making
  * `ProgramCall` a lawful functor in `I`:
  *
  *   - `call.mapInput(identity) == call`
  *   - `call.mapInput(f).mapInput(g) == call.mapInput(g compose f)`
  *
  * [[config]] is the provider option bag forwarded to the LM; framework controls remain typed fields. [[rolloutId]] is
  * the cache-busting selector used by repeated sampling. Typed modules normally receive `ProgramCall[I]` and map it
  * through their input `Shape`; the engine receives `ProgramCall[DynamicValue.Record]`.
  */
final case class ProgramCall[I](
    input: I,
    config: DynamicValue.Record = DynamicValue.Record.empty,
    traceEnabled: Boolean = true,
    rolloutId: Option[Int] = None
):
  /** Change the input carrier without changing execution controls. */
  def mapInput[J](f: I => J): ProgramCall[J] =
    ProgramCall(f(input), config, traceEnabled, rolloutId)

  // Per-call memo of the encoded input record, keyed by the encoding Shape's identity. `Module.apply` and a typed
  // module's `forward` both need the encoding. Benign races may duplicate a pure encode but cannot change its value.
  @volatile private var cachedEncoding: (AnyRef, DynamicValue.Record) = null

  /** Encode `input` through `shape`, memoized per call instance (see the field note above). */
  private[dspy4s] def encodedInput(shape: dspy4s.typed.Shape[I]): DynamicValue.Record =
    val cached = cachedEncoding
    if (cached ne null) && (cached._1 eq shape) then cached._2
    else
      val computed = shape.encode(input)
      cachedEncoding = (shape, computed)
      computed

  /** Map this typed call onto the dynamic record spine while reusing the memoized encoding. */
  private[dspy4s] def encoded(shape: dspy4s.typed.Shape[I]): ProgramCall[DynamicValue.Record] =
    mapInput(_ => encodedInput(shape))
