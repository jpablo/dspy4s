package dspy4s.programs.plan

import dspy4s.programs.plan.Program.Node

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

  def from[I, O](program: Program[I, O]): ProgramGraph =
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
        case _: Node.Identity[?] =>
          nodes += ProgramGraphNode(id, "identity", "identity")

        case _: Node.Lift[?, ?] =>
          nodes += ProgramGraphNode(id, "lift", "pure function")

        case _: Node.LiftEither[?, ?] =>
          nodes += ProgramGraphNode(id, "lift_either", "fallible function")

        case predict: Node.Predict[?, ?] =>
          nodes += ProgramGraphNode(id, "predict", predict.spec.name, Some(predict.spec.parameterId))

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

        case mapped: Node.MapOutput[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "map", "map output")
          pending.prepend(Visit(erase(mapped.inner), Some(id), "inner"))

        case contramapped: Node.ContramapInput[?, ?, ?] =>
          nodes += ProgramGraphNode(id, "contramap", "contramap input")
          pending.prepend(Visit(erase(contramapped.inner), Some(id), "inner"))

        case local: Node.Local[?, ?] =>
          nodes += ProgramGraphNode(id, "local", "local options")
          pending.prepend(Visit(erase(local.inner), Some(id), "inner"))

        case recovered: Node.Recover[?, ?] =>
          nodes += ProgramGraphNode(id, "recover", "recover")
          pending.prepend(Visit(erase(recovered.fallback), Some(id), "fallback"))
          pending.prepend(Visit(erase(recovered.primary), Some(id), "primary"))

        case iterate: Node.Iterate[?, ?] =>
          nodes += ProgramGraphNode(id, "iterate", s"at most ${iterate.maxSteps} steps")
          pending.prepend(Visit(erase(iterate.step), Some(id), "step"))

        case observed: Node.Observe[?, ?] =>
          nodes += ProgramGraphNode(id, "observe", observed.name)
          pending.prepend(Visit(erase(observed.inner), Some(id), "inner"))

    ProgramGraph(nodes.result(), edges.result())

  private def erase[I, O](node: Node[I, O]): Node[?, ?] = node

  private final case class Visit(node: Node[?, ?], parent: Option[Int], role: String)
