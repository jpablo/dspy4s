package dspy4s.harness.repl

import dspy4s.harness.kernel.{CellOutcome, Entry, Logged}
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

/** The note of lost names (6): each name that a completed cell defines, with the `seq` of the `Acted` entry of that
  * cell, in the order of the cells. A later cell that defines a name again moves the name to that cell. A failed cell
  * and a cell that ended by `submit` define nothing, as in the REPL. The note comes from the journal alone, so it is
  * the same after a crash, a reopen and a change of mode.
  */
def lostNames(journal: Vector[Logged]): Vector[(String, Long)] =
  // The open cell: the seq of its `Acted` entry and its code.
  val (_, names) = journal.foldLeft((Option.empty[(Long, String)], Vector.empty[(String, Long)])) {
    case ((_, found), Logged(seq, _, Entry.Acted(_, _, Some(code))))                       => (Some(seq -> code), found)
    case ((Some((cell, code)), found), Logged(_, _, Entry.Observed(_, CellOutcome.Ok, _))) =>
      val defined = topLevelNames(code).distinct
      (None, found.filterNot((name, _) => defined.contains(name)) ++ defined.map(_ -> cell))
    case ((_, found), Logged(_, _, Entry.Observed(_, _, _))) => (None, found)
    case (other, _)                                          => other
  }
  names

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
