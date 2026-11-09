package dspy4s.harness.repl

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourceFile
import dotty.tools.repl.{ParseResult, Parsed, ReplDriver, State}

import java.io.{OutputStream, PrintStream}

/** The top-level names that a cell defines (6), from the parser of the compiler only: `val`, `lazy val`, `def`,
  * `class`, `trait`, `object`, `enum`, `type`, the names in a pattern, extension methods and imports. The note of lost
  * names folds them over the cells that completed, so it needs no state of the old REPL. A cell that does not parse
  * defines nothing.
  */
def topLevelNames(code: String): Vector[String] =
  given state: State = parserState
  given Context      = state.context
  ParseResult(SourceFile.virtual("cell", code)) match
    case parsed: Parsed => parsed.trees.toVector.flatMap {
        case d: untpd.ValDef if !d.name.isEmpty => Vector(d.name.toString)
        case d: untpd.DefDef                    => Vector(d.name.toString)
        case d: untpd.TypeDef                   => Vector(d.name.toString)
        case d: untpd.ModuleDef                 => Vector(d.name.toString)
        case untpd.PatDef(_, pats, _, _)        => pats.flatMap(patternNames).toVector
        case untpd.ExtMethods(_, methods)       => methods.collect { case d: untpd.DefDef => d.name.toString }.toVector
        case i: untpd.Import                    => i.selectors.filterNot(_.isWildcard).map(_.rename.toString).toVector
        case _                                  => Vector.empty
      }
    case _ => Vector.empty

/** The names that a pattern binds, for example `left` and `right` in `val (left, right) = ...`. */
private def patternNames(tree: untpd.Tree)(using Context): List[String] =
  val names = new untpd.UntypedTreeAccumulator[List[String]]:
    def apply(found: List[String], tree: untpd.Tree)(using Context): List[String] =
      tree match
        case untpd.Bind(n, body)                                                       => apply(n.toString :: found, body)
        case untpd.Ident(n) if n.isTermName && n.toString.headOption.exists(_.isLower) => n.toString :: found
        case _                                                                         => foldOver(found, tree)
  names(Nil, tree).reverse

/** A REPL state for the parser. It compiles nothing, so its output goes nowhere. */
private lazy val parserState: State =
  val silent = PrintStream(OutputStream.nullOutputStream())
  ReplDriver(Array("-usejavacp", "-color:never"), silent).initialState
