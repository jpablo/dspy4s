package dspy4s.core

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.primitives.BaseModule
import dspy4s.core.primitives.BasicParameter
import munit.FunSuite

class ModuleGraphWalkerSuite extends FunSuite:
  private class NoopModule(val moduleName: String) extends Module[Map[String, Any], String]:
    override def run(input: Map[String, Any])(using RuntimeContext): Either[DspyError, String] = Right("ok")

  private class Parent extends BaseModule:
    private val p1 = BasicParameter("p1")
    private val child = Child()
    private val leaf = new NoopModule("leaf")

    override protected def members: Map[String, Any] = Map(
      "p1" -> p1,
      "child" -> child,
      "leaves" -> List(leaf)
    )

  private class Child extends BaseModule, Module[Map[String, Any], String]:
    private val p2 = BasicParameter("p2")
    override protected def members: Map[String, Any] = Map("p2" -> p2)
    override val moduleName: String = "child"
    override def run(input: Map[String, Any])(using RuntimeContext): Either[DspyError, String] = Right("child-ok")

  test("collect nested parameters without duplicates") {
    val parent = Parent()
    val named = parent.namedParameters.map(_._1).sorted

    assertEquals(named, Vector("child.p2", "p1"))
  }

  test("collect nested sub modules") {
    val parent = Parent()
    val names = parent.namedSubModules.map(_._1).sorted

    assertEquals(names, Vector("child", "leaves[0]"))
  }
