/**
 * Automated Code Generation from Documentation with DSPy
 *
 * Source:   docs/docs/tutorials/sample_code_generation/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/sample_code_generation/index.md
 * Status:   translated (the DSPy core: the two signatures, the refine CoT, and the composing agent,
 *           from snippets 1/2/3/4). Everything else in the tutorial is plain I/O around DSPy and is out
 *           of scope: the `DocumentationFetcher` (requests + BeautifulSoup + html2text), the interactive
 *           `input()` console session, and JSON file saving. `learnFromDocs` therefore takes the already-
 *           combined documentation text instead of fetching it.
 *
 * `dspy.Signature` classes with `list[str]` fields become `Spec` traits with `OutputField[List[String]]`;
 * the inline-string signature `"code, feedback -> improved_code: str, changes_made: list[str]"` ports as
 * a typed `Signature.fromString` (so `changes_made` decodes to `List[String]`). The `DocumentationLearningAgent`
 * (a `dspy.Module` composing three `ChainOfThought`s) becomes a plain class threading their typed outputs.
 */
package dspy4s.examples.tutorials.sample_code_generation

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ChainOfThought
import dspy4s.typed.{InputField, OutputField, Signature, Spec}

// ── Snippet 1 — the two analysis/generation signatures (top-level traits for Mirror derivation) ──
// | class LibraryAnalyzer(dspy.Signature): """Analyze library documentation ..."""
// --8<-- [start:signatures]
trait LibraryAnalyzer extends Spec:
  def library_name:          InputField[String]
  def documentation_content: InputField[String]
  def core_concepts:     OutputField[List[String]]
  def common_patterns:   OutputField[List[String]]
  def key_methods:       OutputField[List[String]]
  def installation_info: OutputField[String]
  def code_examples:     OutputField[List[String]]
// --8<-- [end:signatures]

// | class CodeGenerator(dspy.Signature): """Generate code examples for specific use cases ..."""
trait CodeGenerator extends Spec:
  def library_info:  InputField[String]
  def use_case:      InputField[String]
  def requirements:  InputField[String]
  def code_example:   OutputField[String]
  def explanation:    OutputField[String]
  def best_practices: OutputField[List[String]]
  def imports_needed: OutputField[List[String]]

// | self.refine_code = dspy.ChainOfThought("code, feedback -> improved_code: str, changes_made: list[str]")
// The typed `Signature.fromString` DSL only supports scalar field types; the `list[str]` output means this
// inline string signature becomes a `Spec` trait (the same shape, with `changes_made` typed as `List[String]`).
trait RefineCode extends Spec:
  def code:     InputField[String]
  def feedback: InputField[String]
  def improved_code: OutputField[String]
  def changes_made:  OutputField[List[String]]

/** What the agent learns about a library — the analyzer's outputs plus the library name. */
case class LibraryInfo(
    library: String,
    coreConcepts: List[String],
    patterns: List[String],
    methods: List[String],
    installation: String,
    examples: List[String]
)

/** A generated, explained code example for one use case. */
case class GeneratedExample(
    code: String,
    explanation: String,
    bestPractices: List[String],
    imports: List[String]
)

object SampleCodeGeneration:

  // ── Snippet 1 — the composing agent (a dspy.Module → a plain class) ──
  // | class DocumentationLearningAgent(dspy.Module):
  // |     self.analyze_docs = dspy.ChainOfThought(LibraryAnalyzer)
  // |     self.generate_code = dspy.ChainOfThought(CodeGenerator)
  // |     self.refine_code = dspy.ChainOfThought("code, feedback -> improved_code: str, changes_made: list[str]")
  // --8<-- [start:agent-predictors]
  final class DocumentationLearningAgent:
    private val analyzeDocs  = ChainOfThought(Signature.of[LibraryAnalyzer])
    private val generateCode = ChainOfThought(Signature.of[CodeGenerator])
    private val refineCode   = ChainOfThought(Signature.of[RefineCode])
    // --8<-- [end:agent-predictors]

    /** Python's `learn_from_urls`, minus the fetching: analyze already-combined documentation text. */
    def learnFromDocs(libraryName: String, combinedContent: String)(using RuntimeContext)
        : Either[DspyError, LibraryInfo] =
      analyzeDocs.apply((library_name = libraryName, documentation_content = combinedContent)).map { analysis =>
        LibraryInfo(
          library      = libraryName,
          coreConcepts = analysis.output.core_concepts,
          patterns     = analysis.output.common_patterns,
          methods      = analysis.output.key_methods,
          installation = analysis.output.installation_info,
          examples     = analysis.output.code_examples
        )
      }

    /** Python's `generate_example`: format the learned info into the generator's `library_info` text. */
    def generateExample(info: LibraryInfo, useCase: String, requirements: String = "")(using RuntimeContext)
        : Either[DspyError, GeneratedExample] =
      val infoText =
        s"""Library: ${info.library}
           |Core Concepts: ${info.coreConcepts.mkString(", ")}
           |Common Patterns: ${info.patterns.mkString(", ")}
           |Key Methods: ${info.methods.mkString(", ")}
           |Installation: ${info.installation}
           |Example Code Snippets: ${info.examples.take(3).mkString("; ")}""".stripMargin
      generateCode.apply((library_info = infoText, use_case = useCase, requirements = requirements)).map { r =>
        GeneratedExample(
          code          = r.output.code_example,
          explanation   = r.output.explanation,
          bestPractices = r.output.best_practices,
          imports       = r.output.imports_needed
        )
      }

    /** Python's `refine_code` CoT: improve code given feedback, reporting the changes made. */
    def refine(code: String, feedback: String)(using RuntimeContext): Either[DspyError, (String, List[String])] =
      refineCode.apply((code = code, feedback = feedback))
        .map(r => (r.output.improved_code, r.output.changes_made))

  // ── Snippets 2/3/4 — fetch docs, learn, generate per use case ──
  // The URL fetching (`requests`/BeautifulSoup) and the interactive `input()` console loop are out of
  // scope; the DSPy flow is: learn from combined docs, then generate one example per use case.
  val defaultUseCases: Vector[String] = Vector(
    "Basic setup and hello world example",
    "Common operations and workflows",
    "Advanced usage with best practices"
  )

  // --8<-- [start:learn-and-generate]
  def learnAndGenerate(
      libraryName: String,
      combinedContent: String,
      useCases: Vector[String] = defaultUseCases
  )(using RuntimeContext): Either[DspyError, (LibraryInfo, Vector[GeneratedExample])] =
    val agent = new DocumentationLearningAgent
    for
      info <- agent.learnFromDocs(libraryName, combinedContent)
      examples <- useCases.foldLeft[Either[DspyError, Vector[GeneratedExample]]](Right(Vector.empty)) {
                    (acc, useCase) =>
                      for
                        sofar <- acc
                        ex <- agent.generateExample(
                                info, useCase, requirements = "Include error handling, comments, and best practices")
                      yield sofar :+ ex
                  }
    yield (info, examples)
  // --8<-- [end:learn-and-generate]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.sample_code_generation.sampleCodeGenerationMain"
@main def sampleCodeGenerationMain(): Unit = Demo.withLm {
  val docs =
    """FastAPI is a modern, fast web framework for building APIs with Python based on standard type hints.
      |Install with `pip install fastapi uvicorn`. Define routes with @app.get decorators on an app = FastAPI()
      |instance. Run with `uvicorn main:app --reload`.""".stripMargin
  SampleCodeGeneration.learnAndGenerate("FastAPI", docs, useCases = Vector("Basic setup and hello world example")) match
    case Left(err) => println(s"⚠️  ${err.message}")
    case Right((info, examples)) =>
      println(s"🔍 ${info.library} core concepts: ${info.coreConcepts}")
      examples.foreach(ex => println(s"\n💻 Code:\n${ex.code}\n📦 Imports: ${ex.imports}"))
}
