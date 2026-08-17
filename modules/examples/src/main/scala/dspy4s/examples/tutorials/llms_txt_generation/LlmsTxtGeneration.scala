/** Generating llms.txt for Code Documentation with DSPy
  *
  * Source: docs/docs/tutorials/llms_txt_generation/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/llms_txt_generation/index.md Status: translated
  * (signatures + the composed pipeline, snippets 1/2/4). The GitHub HTTP fetching (snippet 3: `requests` + base64) is
  * out of scope — it's plain I/O, not a dspy feature.
  *
  * Python's `class RepositoryAnalyzer(dspy.Module)` composing four `ChainOfThought`s becomes a plain class holding four
  * `ChainOfThought` fields whose `forward` threads their outputs through an `Either` for-comprehension. `list[str]`
  * fields map to `List[String]`.
  */
package dspy4s.examples.tutorials.llms_txt_generation

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.{PredictionBackend, Program}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}

// ── Snippet 1 (lines 23–57) — the three analysis signatures (top-level for Mirror) ──
// --8<-- [start:signatures]
trait AnalyzeRepository extends Spec:
  def repo_url: InputField[String]
  def file_tree: InputField[String]
  def readme_content: InputField[String]
  def project_purpose: OutputField[String]
  def key_concepts: OutputField[List[String]]
  def architecture_overview: OutputField[String]

trait AnalyzeCodeStructure extends Spec:
  def file_tree: InputField[String]
  def package_files: InputField[String]
  def important_directories: OutputField[List[String]]
  def entry_points: OutputField[List[String]]
  def development_info: OutputField[String]

trait GenerateLLMsTxt extends Spec:
  def project_purpose: InputField[String]
  def key_concepts: InputField[List[String]]
  def architecture_overview: InputField[String]
  def important_directories: InputField[List[String]]
  def entry_points: InputField[List[String]]
  def development_info: InputField[String]
  def usage_examples: InputField[String]
  def llms_txt_content: OutputField[String]
// --8<-- [end:signatures]

case class RepositoryInput(repoUrl: String, fileTree: String, readmeContent: String, packageFiles: String)
private case class RepositoryInfo(
    input               : RepositoryInput,
    purpose             : String,
    concepts            : List[String],
    architectureOverview: String
)
private case class RepositoryStructure(
    info                : RepositoryInfo,
    importantDirectories: List[String],
    entryPoints         : List[String],
    developmentInfo     : String
)
private case class RepositoryWithExamples(structure: RepositoryStructure, usageExamples: String)

object LlmsTxtGeneration:

  // ── Snippet 2 (lines 61–105) — the composed module ──
  // | class RepositoryAnalyzer(dspy.Module):
  // |     def __init__(self):
  // |         self.analyze_repo = dspy.ChainOfThought(AnalyzeRepository)
  // |         self.analyze_structure = dspy.ChainOfThought(AnalyzeCodeStructure)
  // |         self.generate_examples = dspy.ChainOfThought("repo_info -> usage_examples")
  // |         self.generate_llms_txt = dspy.ChainOfThought(GenerateLLMsTxt)
  // |     def forward(self, repo_url, file_tree, readme_content, package_files): ...
  // --8<-- [start:analyzer]
  object RepositoryAnalyzer:
    private val analyzeRepo      = Program.predict(Signature.of[AnalyzeRepository])
    private val analyzeStructure = Program.predict(Signature.of[AnalyzeCodeStructure])
    private val generateExamples = Program.predict(
      Signature.fromString("repo_info -> usage_examples")
    )
    private val generateLlmsTxt = Program.predict(Signature.of[GenerateLLMsTxt])

    private val repositoryInfo = (
      Program.identity[RepositoryInput] &&& analyzeRepo.contramap[RepositoryInput](input =>
        (repo_url = input.repoUrl, file_tree = input.fileTree, readme_content = input.readmeContent)
      )
    ).map { case (input, result) =>
      RepositoryInfo(input, result.project_purpose, result.key_concepts, result.architecture_overview)
    }

    private val structure = repositoryInfo >>> (
      Program.identity[RepositoryInfo] &&& analyzeStructure.contramap[RepositoryInfo](info =>
        (file_tree = info.input.fileTree, package_files = info.input.packageFiles)
      )
    ).map { case (info, result) =>
      RepositoryStructure(info, result.important_directories, result.entry_points, result.development_info)
    }

    private val examples = structure >>> (
      Program.identity[RepositoryStructure] &&& generateExamples.contramap[RepositoryStructure](value =>
        (repo_info = s"Purpose: ${value.info.purpose}\nConcepts: ${value.info.concepts.mkString(", ")}")
      )
    ).map { case (value, result) => RepositoryWithExamples(value, result.usage_examples) }

    val program = examples >>> generateLlmsTxt.contramap[RepositoryWithExamples] { value =>
      val structure = value.structure
      val info      = structure.info
      (
        project_purpose = info.purpose,
        key_concepts = info.concepts,
        architecture_overview = info.architectureOverview,
        important_directories = structure.importantDirectories,
        entry_points = structure.entryPoints,
        development_info = structure.developmentInfo,
        usage_examples = value.usageExamples
      )
    }
  // --8<-- [end:analyzer]

  // ── Snippet 3 (lines 95–153) — gather repo info over the GitHub API ──
  // Out of scope: plain HTTP (`requests` + base64) to fetch the file tree / README / package files.
  // Supply `fileTree` / `readmeContent` / `packageFiles` to `forward` however you like.

  // ── Snippet 4 (lines 175–210) — run the generator ──
  // | analyzer = RepositoryAnalyzer(); result = analyzer(repo_url=..., file_tree=..., ...)
  def generateLlmsTxt(
      repoUrl      : String,
      fileTree     : String,
      readmeContent: String,
      packageFiles : String
  )(using PredictionBackend): Either[DspyError, String] =
    Demo.run(RepositoryAnalyzer.program, RepositoryInput(repoUrl, fileTree, readmeContent, packageFiles))
      .map(_.output.llms_txt_content)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.llms_txt_generation.llmsTxtMain"
@main def llmsTxtMain(): Unit =
  Demo.withLm {
    val result = LlmsTxtGeneration.generateLlmsTxt(
      repoUrl = "https://github.com/example/project",
      fileTree = "src/main.py\nsrc/util.py\nREADME.md\npyproject.toml",
      readmeContent = "# Project\nA small example project.",
      packageFiles = "=== pyproject.toml ===\n[project]\nname = \"project\""
    )
    println("llms.txt: " + result)
  }
