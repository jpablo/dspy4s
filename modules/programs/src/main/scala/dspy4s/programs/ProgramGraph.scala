package dspy4s.programs

import dspy4s.programs.Program.Node

import scala.collection.mutable.ArrayDeque

/** One structural node in a program graph. */
final case class ProgramGraphNode(
    id         : Int,
    kind       : String,
    label      : String,
    parameterId: Option[ParameterId] = None
)

/** A labeled parent-to-child relation in a program graph. */
final case class ProgramGraphEdge(from: Int, to: Int, role: String)

/** Stack-safe structural description of a program. */
final case class ProgramGraph(nodes: Vector[ProgramGraphNode], edges: Vector[ProgramGraphEdge]):
  def parameterNodes: Vector[ProgramGraphNode] = nodes.filter(_.parameterId.nonEmpty)

object ProgramGraph:

  def from[I, O, R](program: ProgramWithEnv[I, O, R]): ProgramGraph =
    val pending = ArrayDeque(Visit(erase(program.root), None, "root"))
    val nodes   = Vector.newBuilder[ProgramGraphNode]
    val edges   = Vector.newBuilder[ProgramGraphEdge]
    var nextId  = 0

    while pending.nonEmpty do
      val visit = pending.removeHead()
      val id    = nextId
      nextId += 1

      visit.parent.foreach(parent => edges += ProgramGraphEdge(parent, id, visit.role))

      visit.node match
        case _: Node.Identity[?] => nodes += ProgramGraphNode(id, "identity", "identity")

        case _: Node.Lift[?, ?] => nodes += ProgramGraphNode(id, "lift", "pure function")

        case _: Node.LiftEither[?, ?] => nodes += ProgramGraphNode(id, "lift_either", "fallible function")

        case predict: Node.Predict[?, ?] => nodes +=
            ProgramGraphNode(id, "predict", predict.spec.name, Some(predict.spec.parameterId))

        case _: Node.ExecuteCode => nodes += ProgramGraphNode(id, "execute_code", "execute generated code")

        case _: Node.InvokeTool => nodes += ProgramGraphNode(id, "invoke_tool", "invoke host tool")

        case _: Node.ExecuteRepl => nodes += ProgramGraphNode(id, "execute_repl", "execute in persistent REPL")

        case sequential: Node.AndThen[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "and_then", ">>>")
          pending.prepend(Visit(erase(sequential.second), Some(id), "second"))
          pending.prepend(Visit(erase(sequential.first), Some(id), "first"))

        case fanout: Node.Fanout[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "fanout", "&&&")
          pending.prepend(Visit(erase(fanout.right), Some(id), "right"))
          pending.prepend(Visit(erase(fanout.left), Some(id), "left"))

        case split: Node.Split[?, ?, ?, ?] =>
          nodes += ProgramGraphNode(id, "split", "***")
          pending.prepend(Visit(erase(split.right), Some(id), "right"))
          pending.prepend(Visit(erase(split.left), Some(id), "left"))

        case choice: Node.Choice[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "choice", "|||")
          pending.prepend(Visit(erase(choice.right), Some(id), "right"))
          pending.prepend(Visit(erase(choice.left), Some(id), "left"))

        case mapped: Node.MapOutput[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "map", "map output")
          pending.prepend(Visit(erase(mapped.inner), Some(id), "inner"))

        case contramapped: Node.ContramapInput[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "contramap", "contramap input")
          pending.prepend(Visit(erase(contramapped.inner), Some(id), "inner"))

        case local: Node.Local[?, ?] =>
          nodes += ProgramGraphNode(id, "local", "local options")
          pending.prepend(Visit(erase(local.inner), Some(id), "inner"))

        case local: Node.LocalWithInput[?, ?] =>
          nodes += ProgramGraphNode(id, "local_input", "input-aware local options")
          pending.prepend(Visit(erase(local.inner), Some(id), "inner"))

        case local: Node.LocalParameters[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "local_parameters", "run-local parameters")
          pending.prepend(Visit(erase(local.inner), Some(id), "inner"))
          pending.prepend(Visit(erase(local.configurator), Some(id), "configurator"))

        case recovered: Node.Recover[?, ?] =>
          nodes += ProgramGraphNode(id, "recover", "recover")
          pending.prepend(Visit(erase(recovered.fallback), Some(id), "fallback"))
          pending.prepend(Visit(erase(recovered.primary), Some(id), "primary"))

        case attempted: Node.Attempt[?, ?] =>
          nodes += ProgramGraphNode(id, "attempt", "failure as data")
          pending.prepend(Visit(erase(attempted.inner), Some(id), "inner"))

        case captured: Node.WithEvidence[?, ?] =>
          nodes += ProgramGraphNode(id, "with_evidence", "typed output and raw prediction")
          pending.prepend(Visit(erase(captured.inner), Some(id), "inner"))

        case restored: Node.FromEvidence[?, ?] =>
          nodes += ProgramGraphNode(id, "from_evidence", "restore typed prediction evidence")
          pending.prepend(Visit(erase(restored.inner), Some(id), "inner"))

        case collected: Node.CollectAll[?, ?] =>
          nodes += ProgramGraphNode(id, "collect_all", s"${collected.members.size} members")
          collected.members.zipWithIndex.reverseIterator.foreach { case (member, index) =>
            pending.prepend(Visit(erase(member), Some(id), s"member_$index"))
          }

        case collected: Node.CollectAllPar[?, ?] =>
          nodes += ProgramGraphNode(
            id,
            "collect_all_par",
            s"${collected.members.size} members, parallelism ${collected.parallelism}"
          )
          collected.members.zipWithIndex.reverseIterator.foreach { case (member, index) =>
            pending.prepend(Visit(erase(member), Some(id), s"member_$index"))
          }

        case iterate: Node.Iterate[?, ?] =>
          nodes += ProgramGraphNode(id, "iterate", s"at most ${iterate.maxSteps} steps")
          pending.prepend(Visit(erase(iterate.step), Some(id), "step"))

        case selection: Node.BestOfN[?, ?] =>
          nodes += ProgramGraphNode(id, "best_of_n", s"${selection.attempts} attempts")
          pending.prepend(Visit(erase(selection.inner), Some(id), "candidate"))

        case observed: Node.Observe[?, ?] =>
          nodes += ProgramGraphNode(id, "observe", observed.name)
          pending.prepend(Visit(erase(observed.inner), Some(id), "inner"))

    ProgramGraph(nodes.result(), edges.result())

  private def erase[I, O](node: Node[I, O]): Node[?, ?] = node

  private final case class Visit(node: Node[?, ?], parent: Option[Int], role: String)
