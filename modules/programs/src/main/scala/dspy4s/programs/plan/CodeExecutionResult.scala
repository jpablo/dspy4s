package dspy4s.programs.plan

/** Domain result of one generated-code execution. Infrastructure failures stay in the typed error channel. */
enum CodeExecutionResult:
  case Succeeded(output: String)
  case Failed(error: String)
