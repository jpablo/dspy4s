package dspy4s.core.contracts

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait Parameter:
  def name: String
  def reset(): Parameter

trait Module[-In, +Out]:
  def moduleName: String

  def run(input: In)(using RuntimeContext): Either[DspyError, Out]

  def arun(input: In)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, Out]] =
    Future.successful(run(input))

trait StatefulModule[-In, +Out] extends Module[In, Out]:
  def dumpState: Map[String, Any]
  def loadState(state: Map[String, Any]): Either[DspyError, StatefulModule[In, Out]]

trait ModuleGraph:
  def namedParameters: Vector[(String, Parameter)]
  def namedSubModules: Vector[(String, Module[?, ?])]
