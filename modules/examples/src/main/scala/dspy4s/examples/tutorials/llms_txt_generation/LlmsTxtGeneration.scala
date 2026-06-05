/**
 * Generating llms.txt for Code Documentation with DSPy
 *
 * Source:   docs/docs/tutorials/llms_txt_generation/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/llms_txt_generation/index.md
 * Status:   translated (signatures + the composed pipeline, snippets 1/2/4). The GitHub HTTP fetching
 *           (snippet 3: `requests` + base64) is out of scope — it's plain I/O, not a dspy feature.
 *
 * Python's `class RepositoryAnalyzer(dspy.Module)` composing four `ChainOfThought`s becomes a plain
 * class holding four typed `ChainOfThought` fields whose `forward` threads their typed outputs through
 * an `Either` for-comprehension. `list[str]` fields map to `List[String]`.
 */
package dspy4s.examples.tutorials.llms_txt_generation

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ChainOfThought
import dspy4s.typed.{InputField, OutputField, Signature, Spec}

// ── Snippet 1 (lines 23–57) — the three analysis signatures (top-level for Mirror) ──
trait AnalyzeRepository extends Spec:
  def repo_url:       InputField[String]
  def file_tree:      InputField[String]
  def readme_content: InputField[String]
  def project_purpose:       OutputField[String]
  def key_concepts:          OutputField[List[String]]
  def architecture_overview: OutputField[String]

trait AnalyzeCodeStructure extends Spec:
  def file_tree:     InputField[String]
  def package_files: InputField[String]
  def important_directories: OutputField[List[String]]
  def entry_points:          OutputField[List[String]]
  def development_info:      OutputField[String]

trait GenerateLLMsTxt extends Spec:
  def project_purpose:       InputField[String]
  def key_concepts:          InputField[List[String]]
  def architecture_overview: InputField[String]
  def important_directories: InputField[List[String]]
  def entry_points:          InputField[List[String]]
  def development_info:      InputField[String]
  def usage_examples:        InputField[String]
  def llms_txt_content: OutputField[String]

object LlmsTxtGeneration:

  // ── Snippet 2 (lines 61–105) — the composed module ──
  // | class RepositoryAnalyzer(dspy.Module):
  // |     def __init__(self):
  // |         self.analyze_repo = dspy.ChainOfThought(AnalyzeRepository)
  // |         self.analyze_structure = dspy.ChainOfThought(AnalyzeCodeStructure)
  // |         self.generate_examples = dspy.ChainOfThought("repo_info -> usage_examples")
  // |         self.generate_llms_txt = dspy.ChainOfThought(GenerateLLMsTxt)
  // |     def forward(self, repo_url, file_tree, readme_content, package_files): ...
  final class RepositoryAnalyzer:
    private val analyzeRepo      = ChainOfThought(Signature.of[AnalyzeRepository])
    private val analyzeStructure = ChainOfThought(Signature.of[AnalyzeCodeStructure])
    private val generateExamples = ChainOfThought(Signature.fromString("repo_info -> usage_examples"))
    private val generateLlmsTxt  = ChainOfThought(Signature.of[GenerateLLMsTxt])

    def forward(
        repoUrl: String,
        fileTree: String,
        readmeContent: String,
        packageFiles: String
    )(using RuntimeContext): Either[DspyError, String] =
      for
        repo <- analyzeRepo.apply((repo_url = repoUrl, file_tree = fileTree, readme_content = readmeContent))
        structure <- analyzeStructure.apply((file_tree = fileTree, package_files = packageFiles))
        examples <- generateExamples.apply(
                      (repo_info = s"Purpose: ${repo.output.project_purpose}\nConcepts: ${repo.output.key_concepts}")
                    )
        llms <- generateLlmsTxt.apply((
                  project_purpose       = repo.output.project_purpose,
                  key_concepts          = repo.output.key_concepts,
                  architecture_overview = repo.output.architecture_overview,
                  important_directories = structure.output.important_directories,
                  entry_points          = structure.output.entry_points,
                  development_info      = structure.output.development_info,
                  usage_examples        = examples.output.usage_examples
                ))
      yield llms.output.llms_txt_content

  // ── Snippet 3 (lines 95–153) — gather repo info over the GitHub API ──
  // Out of scope: plain HTTP (`requests` + base64) to fetch the file tree / README / package files.
  // Supply `fileTree` / `readmeContent` / `packageFiles` to `forward` however you like.

  // ── Snippet 4 (lines 175–210) — run the generator ──
  // | analyzer = RepositoryAnalyzer(); result = analyzer(repo_url=..., file_tree=..., ...)
  def generateLlmsTxt(
      repoUrl: String,
      fileTree: String,
      readmeContent: String,
      packageFiles: String
  )(using RuntimeContext): Either[DspyError, String] =
    new RepositoryAnalyzer().forward(repoUrl, fileTree, readmeContent, packageFiles)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.llms_txt_generation.llmsTxtMain"
@main def llmsTxtMain(): Unit = Demo.withLm {
  val result = LlmsTxtGeneration.generateLlmsTxt(
    repoUrl       = "https://github.com/example/project",
    fileTree      = "src/main.py\nsrc/util.py\nREADME.md\npyproject.toml",
    readmeContent = "# Project\nA small example project.",
    packageFiles  = "=== pyproject.toml ===\n[project]\nname = \"project\""
  )
  println("llms.txt: " + result)
}
