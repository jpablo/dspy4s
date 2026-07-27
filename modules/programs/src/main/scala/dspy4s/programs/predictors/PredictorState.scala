package dspy4s.programs.predictors

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError}
import dspy4s.core.data.Example
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The complete writable state of one optimizer-addressable predictor.
  *
  * This value is deliberately smaller than [[DynamicPredict]]. A predictor's signature shape, module name, runtime,
  * output schema, bound language model, and tools describe or execute the predictor; they are not prompt parameters and
  * must not be replaceable by an optimizer. Keeping only the three fields every supported predictor can write makes
  * [[PredictorLens]] a lawful state lens and gives [[PredictorTraversal]] and Para one homogeneous parameter carrier.
  *
  * @param instructions
  *   signature-level prompt instructions
  * @param demos
  *   few-shot examples rendered by the adapter
  * @param config
  *   module-level language-model option defaults
  */
final case class PredictorState(
    instructions: Option[String] = None,
    demos: Vector[Example] = Vector.empty,
    config: DynamicValue.Record = DynamicValue.Record.empty
) derives CanEqual:

  /** Encode this state as a data-only record suitable for program persistence. */
  def dumpState: DynamicValue.Record =
    val encodedInstructions: DynamicValue =
      instructions.fold(DynamicValue.Null: DynamicValue)(value =>
        DynamicValue.Primitive(PrimitiveValue.String(value))
      )
    val encodedDemos: Seq[DynamicValue] = demos.map(demo => demo.dumpState: DynamicValue)
    DynamicValue.Record(Chunk.from(Seq(
      "instructions" -> encodedInstructions,
      "demos"        -> DynamicValue.Sequence(Chunk.from(encodedDemos)),
      "config"       -> (config: DynamicValue)
    )))

object PredictorState:

  /** Decode the record produced by [[PredictorState.dumpState]].
    *
    * The codec requires all three keys and is strict about their types. The former executable-predictor state format
    * (`signature` + `demos` + `config`) is intentionally unsupported: this project is unpublished, and carrying that
    * shape forward would blur architecture back into writable state.
    */
  def fromState(state: DynamicValue.Record): Either[DspyError, PredictorState] =
    def readInstructions: Either[DspyError, Option[String]] =
      DynamicValues.recordGet(state, "instructions") match
        case Some(_: DynamicValue.Null.type)                            => Right(None)
        case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) => Right(Some(value))
        case None    => Left(ValidationError("PredictorLens state is missing 'instructions'"))
        case Some(_) => Left(ValidationError("PredictorLens state 'instructions' must be a string or null"))

    def readDemos: Either[DspyError, Vector[Example]] =
      DynamicValues.recordGet(state, "demos") match
        case Some(sequence: DynamicValue.Sequence) =>
          sequence.elements.iterator.foldLeft[Either[DspyError, Vector[Example]]](Right(Vector.empty)) {
            (acc, raw) =>
              for
                demos <- acc
                demo <- raw match
                  case record: DynamicValue.Record => Example.fromState(record)
                  case _ => Left(ValidationError("PredictorLens state 'demos' must contain records"))
              yield demos :+ demo
          }
        case None    => Left(ValidationError("PredictorLens state is missing 'demos'"))
        case Some(_) => Left(ValidationError("PredictorLens state 'demos' must be a sequence"))

    def readConfig: Either[DspyError, DynamicValue.Record] =
      DynamicValues.recordGet(state, "config") match
        case Some(record: DynamicValue.Record) => Right(record)
        case None                              => Left(ValidationError("PredictorLens state is missing 'config'"))
        case Some(_)                           => Left(ValidationError("PredictorLens state 'config' must be a record"))

    for
      instructions <- readInstructions
      demos        <- readDemos
      config       <- readConfig
    yield PredictorState(instructions, demos, config)
