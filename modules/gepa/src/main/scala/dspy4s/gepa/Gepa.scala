package dspy4s.gepa

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.programs.{ProgramWithEnv, RecordProgramWithEnv}
import zio.ZIO

import java.nio.file.Path

/** Functional GEPA entry point. */
object Gepa:

  def apply[I, O, R, RR](
      student  : RecordProgramWithEnv[I, O, R],
      trainset : Vector[Example],
      valset   : Vector[Example],
      metric   : FeedbackMetric,
      reflector: ProgramWithEnv[InstructionProposer.Input, InstructionProposer.Output, RR],
      config   : GepaConfig,
      runDir   : Option[Path] = None
  ): ZIO[R & RR, DspyError, GepaResult[RecordProgramWithEnv[I, O, R]]] =
    val adapter = new GepaAdapter(student, metric, config.failureScore, config.parallelism)
    new GepaEngine(adapter, reflector, config).optimize(Candidate.seed(student), trainset, valset, runDir)
