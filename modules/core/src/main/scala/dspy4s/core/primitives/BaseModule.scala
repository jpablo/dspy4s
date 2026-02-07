package dspy4s.core.primitives

import dspy4s.core.contracts.Module
import dspy4s.core.contracts.ModuleGraph
import dspy4s.core.contracts.Parameter

import scala.collection.mutable

trait BaseModule extends ModuleGraph:
  protected def members: Map[String, Any] = Map.empty
  final def moduleMembers: Map[String, Any] = members

  override final def namedParameters: Vector[(String, Parameter)] =
    ModuleGraphWalker.collectParameters(members)

  override final def namedSubModules: Vector[(String, Module[?, ?])] =
    ModuleGraphWalker.collectSubModules(members)

object ModuleGraphWalker:
  def collectParameters(members: Map[String, Any]): Vector[(String, Parameter)] =
    val visited = mutable.Set.empty[Int]
    val result = mutable.ArrayBuffer.empty[(String, Parameter)]

    def visit(path: String, value: Any): Unit =
      value match
        case null => ()
        case parameter: Parameter =>
          val id = System.identityHashCode(parameter)
          if !visited.contains(id) then
            visited += id
            result += path -> parameter
        case module: Module[?, ?] =>
          module match
            case graph: BaseModule =>
              graph.moduleMembers.foreach { case (childName, childValue) =>
                visit(s"$path.$childName", childValue)
              }
            case _ => ()
        case graph: BaseModule =>
          graph.moduleMembers.foreach { case (childName, childValue) =>
            visit(s"$path.$childName", childValue)
          }
        case map: Map[?, ?] =>
          map.foreach { case (key, item) => visit(s"$path[$key]", item) }
        case seq: Iterable[?] =>
          seq.zipWithIndex.foreach { case (item, index) => visit(s"$path[$index]", item) }
        case _ => ()

    members.foreach { case (name, value) => visit(name, value) }
    result.toVector

  def collectSubModules(members: Map[String, Any]): Vector[(String, Module[?, ?])] =
    val visited = mutable.Set.empty[Int]
    val result = mutable.ArrayBuffer.empty[(String, Module[?, ?])]

    def visit(path: String, value: Any): Unit =
      value match
        case null => ()
        case module: Module[?, ?] =>
          val id = System.identityHashCode(module)
          if !visited.contains(id) then
            visited += id
            result += path -> module
            module match
              case graph: BaseModule =>
                graph.moduleMembers.foreach { case (childName, childValue) =>
                  visit(s"$path.$childName", childValue)
                }
              case _ => ()
        case graph: BaseModule =>
          graph.moduleMembers.foreach { case (childName, childValue) =>
            visit(s"$path.$childName", childValue)
          }
        case map: Map[?, ?] =>
          map.foreach { case (key, item) => visit(s"$path[$key]", item) }
        case seq: Iterable[?] =>
          seq.zipWithIndex.foreach { case (item, index) => visit(s"$path[$index]", item) }
        case _ => ()

    members.foreach { case (name, value) => visit(name, value) }
    result.toVector
