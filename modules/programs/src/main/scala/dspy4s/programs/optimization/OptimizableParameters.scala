package dspy4s.programs.optimization

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError}
import dspy4s.core.data.Example
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The complete optimizer-writable parameters of one optimizable leaf.
  *
  * This value is deliberately smaller than [[DynamicPredict]]. A leaf's signature shape, module name, runtime, output
  * schema, bound language model, and tools describe or execute the leaf; they are not prompt parameters and must not be
  * replaceable by an optimizer. Keeping only the three fields every supported predictor can write makes
  * [[OptimizableLeaf]] a lawful lens and gives [[OptimizableStructure]] and the graded program algebra one homogeneous
  * parameter carrier.
  *
  * @param instructions
  *   signature-level prompt instructions
  * @param demos
  *   few-shot examples rendered by the adapter
  * @param config
  *   module-level language-model option defaults
  */
final case class OptimizableParameters(
    instructions: Option[String]      = None,
    demos       : Vector[Example]     = Vector.empty,
    config      : DynamicValue.Record = DynamicValue.Record.empty
) derives CanEqual:

  /** Encode these parameters as a data-only record suitable for program persistence. */
  def dumpState: DynamicValue.Record =
    val encodedInstructions: DynamicValue = instructions.fold(DynamicValue.Null: DynamicValue)(value =>
      DynamicValue.Primitive(PrimitiveValue.String(value))
    )
    val encodedDemos: Seq[DynamicValue] = demos.map(demo => demo.dumpState: DynamicValue)
    DynamicValue.Record(Chunk.from(Seq(
      "instructions" -> encodedInstructions,
      "demos"        -> DynamicValue.Sequence(Chunk.from(encodedDemos)),
      "config"       -> (config: DynamicValue)
    )))

object OptimizableParameters:

  /** Decode the record produced by [[OptimizableParameters.dumpState]].
    *
    * The codec requires all three keys and is strict about their types. The former executable-predictor state format
    * (`signature` + `demos` + `config`) is intentionally unsupported: this project is unpublished, and carrying that
    * shape forward would blur architecture back into writable state.
    */
  def fromState(state: DynamicValue.Record): Either[DspyError, OptimizableParameters] =
    def readInstructions: Either[DspyError, Option[String]] =
      DynamicValues.recordGet(state, "instructions") match
        case Some(_: DynamicValue.Null.type)                            => Right(None)
        case Some(DynamicValue.Primitive(PrimitiveValue.String(value))) => Right(Some(value))
        case None                                                       => Left(ValidationError("OptimizableParameters is missing 'instructions'"))
        case Some(_)                                                    => Left(ValidationError("OptimizableParameters 'instructions' must be a string or null"))

    def readDemos: Either[DspyError, Vector[Example]] =
      DynamicValues.recordGet(state, "demos") match
        case Some(sequence: DynamicValue.Sequence) =>
          sequence.elements.iterator.foldLeft[Either[DspyError, Vector[Example]]](Right(Vector.empty)) {
            (acc, raw) =>
              for
                demos <- acc
                demo  <- raw match
                          case record: DynamicValue.Record => Example.fromState(record)
                          case _                           => Left(ValidationError("OptimizableParameters 'demos' must contain records"))
              yield demos :+ demo
          }
        case None    => Left(ValidationError("OptimizableParameters is missing 'demos'"))
        case Some(_) => Left(ValidationError("OptimizableParameters 'demos' must be a sequence"))

    def readConfig: Either[DspyError, DynamicValue.Record] =
      DynamicValues.recordGet(state, "config") match
        case Some(record: DynamicValue.Record) => Right(record)
        case None                              => Left(ValidationError("OptimizableParameters is missing 'config'"))
        case Some(_)                           => Left(ValidationError("OptimizableParameters 'config' must be a record"))

    for
      instructions <- readInstructions
      demos        <- readDemos
      config       <- readConfig
    yield OptimizableParameters(instructions, demos, config)
