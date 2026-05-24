/**
 * Tracking DSPy Optimizers with MLflow
 *
 * Source:   docs/docs/tutorials/optimizer_tracking/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/optimizer_tracking/index.md
 * Status:   scaffold (3 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.optimizer_tracking

object OptimizerTracking {

  // ── Snippet 1 (lines 52–66) ────────────────────
  // | import mlflow
  // | import dspy
  // |
  // | # Enable autologging with all features
  // | mlflow.dspy.autolog(
  // |     log_compiles=True,    # Track optimization process
  // |     log_evals=True,       # Track evaluation results
  // |     log_traces_from_compile=True  # Track program traces during optimization
  // | )
  // |
  // | # Configure MLflow tracking
  // | mlflow.set_tracking_uri("http://localhost:5000")  # Use local MLflow server
  // | mlflow.set_experiment("DSPy-Optimization")
  // TODO translate snippet 1

  // ── Snippet 2 (lines 72–98) ────────────────────
  // | import dspy
  // | from dspy.datasets.gsm8k import GSM8K, gsm8k_metric
  // |
  // | # Configure your language model
  // | lm = dspy.LM(model="openai/gpt-4o")
  // | dspy.configure(lm=lm)
  // |
  // | # Load dataset
  // | gsm8k = GSM8K()
  // | trainset, devset = gsm8k.train, gsm8k.dev
  // |
  // | # Define your program
  // | program = dspy.ChainOfThought("question -> answer")
  // |
  // | # Create and run optimizer with tracking
  // | teleprompter = dspy.teleprompt.MIPROv2(
  // |     metric=gsm8k_metric,
  // |     auto="light",
  // | )
  // |
  // | # The optimization process will be automatically tracked
  // | optimized_program = teleprompter.compile(
  // |     program,
  // |     trainset=trainset,
  // | )
  // TODO translate snippet 2

  // ── Snippet 3 (lines 127–130) ────────────────────
  // | model_path = mlflow.artifacts.download_artifacts("mlflow-artifacts:/path/to/best_model.json")
  // | program.load(model_path)
  // TODO translate snippet 3
}
