package dspy4s.programs.compose

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.Prediction

import scala.compiletime.ops.int.+

/** Explicit classification of which typed program failures may activate a fallback.
  *
  * Recovery is a runtime-selected policy value, not a typeclass: several policies can coexist for the same program
  * types, and callers choose one at each recovery boundary. [[Never]] and [[Always]] make the two extremes explicit;
  * [[ErrorCodes]] and [[When]] support selective recovery without hiding the original error taxonomy.
  */
enum RecoveryPolicy derives CanEqual:
  case Never
  case Always
  case ErrorCodes(codes: Set[String])
  case When(predicate: DspyError => Boolean)

  def allows(error: DspyError): Boolean =
    this match
      case Never             => false
      case Always            => true
      case ErrorCodes(codes) => codes.contains(error.code)
      case When(predicate)   => predicate(error)

object RecoveryPolicy:
  def onCodes(first : String, rest: String*): RecoveryPolicy = ErrorCodes((first +: rest).toSet)
  def when(predicate: DspyError => Boolean): RecoveryPolicy  = When(predicate)

/** Try `primary`; on an allowed failure, run `fallback` on the same input and call controls.
  *
  * Both branches remain concrete fields, so optimizer addressability is structural: primary predictors precede fallback
  * predictors. A fallback failure is returned as-is; a denied primary failure remains the original result.
  */
final case class RecoverWith[
    I,
    O,
    P <: Module[I, O],
    F <: Module[I, O]
](primary: P, fallback: F, policy: RecoveryPolicy)
    extends TransparentModule[I, O]:
  override val moduleName: String = "recover_with"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    primary(call) match
      case success @ Right(_)   => success
      case denied @ Left(error) =>
        TransformResult.guard("program_recovery_policy")(Right(policy.allows(error))).flatMap { allowed =>
          if allowed then fallback(call) else denied
        }

object RecoverWith:
  given recoverWithOptimizableTraversal[
      I,
      O,
      P <: Module[I, O],
      F <: Module[I, O],
      NP <: Int,
      NF <: Int
  ](using
      primary: OptimizableTraversal.WithArity[P, NP],
      fallback: OptimizableTraversal.WithArity[F, NF]
  ): OptimizableTraversal.Of[RecoverWith[I, O, P, F], NP + NF] with
    def arity(program: RecoverWith[I, O, P, F]): Int =
      primary.arity(program.primary) + fallback.arity(program.fallback)
    def inspect(program: RecoverWith[I, O, P, F]): Vector[OptimizableView] =
      primary.inspect(program.primary) ++ fallback.inspect(program.fallback)

    def replace(program: RecoverWith[I, O, P, F], updates: Vector[OptimizableParameters]): RecoverWith[I, O, P, F] =
      val (primaryUpdates, fallbackUpdates) = updates.splitAt(primary.read(program.primary).size)
      program.copy(
        primary = primary.replace(program.primary, primaryUpdates),
        fallback = fallback.replace(program.fallback, fallbackUpdates)
      )

    override def inspectNamed(program: RecoverWith[I, O, P, F]): Vector[(String, OptimizableView)] =
      primary.inspectNamed(program.primary).map { case (sub, view) =>
        (if sub == "self" then "primary" else s"primary.$sub") -> view
      } ++ fallback.inspectNamed(program.fallback).map { case (sub, view) =>
        (if sub == "self" then "fallback" else s"fallback.$sub") -> view
      }

extension [I, O, P <: Module[I, O]](self: P)
  /** Add a fixed, optimizer-addressable fallback under an explicit failure-selection policy. */
  def recoverWith[F <: Module[I, O]](policy: RecoveryPolicy)(
      fallback: F
  ): RecoverWith[I, O, P, F] = RecoverWith(self, fallback, policy)
