package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, ValidationError}
import dspy4s.programs.contracts.Prediction

/** Bounded typed refinement with stable-ID advice and conservative best-attempt selection. */
object Refine:

  /** Advice for specific learnable slots. Unknown IDs fail when the next attempt starts. */
  final case class Advice(values: Map[ParameterId, String])

  /** Complete critic input after one sub-threshold attempt. */
  final case class Attempt[I, O](
      input         : I,
      prediction    : Prediction[O],
      score         : Double,
      bestPrediction: Prediction[O],
      bestScore     : Double,
      number        : Int,
      maxAttempts   : Int
  )

  private final case class State[I, O](
      input : I,
      index : Int,
      advice: Advice,
      best  : Option[(Double, Prediction[O])]
  )

  private final case class Scored[I, O](state: State[I, O], prediction: Prediction[O], score: Double)

  def apply[I, O, RT, RA](
      task       : ProgramWithEnv[I, O, RT],
      critic     : ProgramWithEnv[Attempt[I, O], Advice, RA],
      maxAttempts: Int,
      threshold  : Double
  )(
      reward: (I, Prediction[O]) => Either[DspyError, Double]
  ): ProgramWithEnv[I, O, RT & RA] =
    require(maxAttempts > 0, "Refine maxAttempts must be positive")

    val targetIds    = task.parameters.all.map(_.id).toSet
    val configurator = Program.lift[State[I, O], Advice](_.advice)
    val localizedTask = task
      .contramap[State[I, O]](_.input)
      .localParametersWith(configurator)((store, advice) => applyAdvice(store, targetIds, advice))
      .localWithInput { (state, options) =>
        options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
      }
      .withEvidence

    val scored = (Program.identity[State[I, O]] &&& localizedTask) >>>
      Program.liftEither[(State[I, O], Prediction[O]), Scored[I, O]] { case (state, prediction) =>
        reward(state.input, prediction).map(score => Scored(state, prediction, score))
      }

    val decide = Program.lift[Scored[I, O], Either[Prediction[O], Attempt[I, O]]] { current =>
      val (bestScore, bestPrediction) = current.state.best match
        case Some((score, prediction)) if score >= current.score => score -> prediction
        case _                                                   => current.score -> current.prediction
      val number = current.state.index + 1
      if current.score >= threshold || number >= maxAttempts then Left(bestPrediction)
      else
        Right(Attempt(
          input = current.state.input,
          prediction = current.prediction,
          score = current.score,
          bestPrediction = bestPrediction,
          bestScore = bestScore,
          number = number,
          maxAttempts = maxAttempts
        ))
    }

    val finish = Program.lift[Prediction[O], LoopDecision[State[I, O], Prediction[O]]](LoopDecision.Done(_))
    val retry  = (Program.identity[Attempt[I, O]] &&& critic).map { case (attempt, advice) =>
      LoopDecision.Continue[State[I, O], Prediction[O]](State(
        input = attempt.input,
        index = attempt.number,
        advice = advice,
        best = Some(attempt.bestScore -> attempt.bestPrediction)
      ))
    }
    val step    = scored >>> decide >>> (finish ||| retry)
    val initial = Program.lift[I, State[I, O]](input => State(input, 0, Advice(Map.empty), None))

    Program.fromEvidence(initial >>> Program.iterate(step, maxAttempts))

  private def applyAdvice(
      store    : ParameterStore,
      targetIds: Set[ParameterId],
      advice   : Advice
  ): Either[DspyError, ParameterStore] =
    val unknown = advice.values.keySet -- targetIds
    if unknown.nonEmpty then
      Left(ValidationError(
        s"Refine advice contains unknown parameter IDs: ${unknown.toVector.map(_.value).sorted.mkString(", ")}"
      ))
    else
      val replacements = store.all.map { binding =>
        val value = advice.values.get(binding.id).map(_.trim).filter(value => value.nonEmpty && value != "N/A") match
          case None         => binding.value
          case Some(advice) =>
            val base     = binding.value.instructions.map(_.trim).filter(_.nonEmpty).toVector
            val feedback = s"Feedback for this attempt:\n$advice"
            binding.value.copy(instructions = Some((base :+ feedback).mkString("\n\n")))
        binding.id -> value
      }.toMap
      store.replace(replacements)
