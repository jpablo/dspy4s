/**
 * Tutorial: Saving and Loading your DSPy program
 *
 * Source:   docs/docs/tutorials/saving/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/saving/index.md
 * Status:   blocked — program `.save`/`.load` and the GSM8K dataset are not ported
 */
package dspy4s.examples.tutorials.saving

object Saving {

  // ── Snippet 1 (lines 19–31) ────────────────────
  // | import dspy
  // | from dspy.datasets.gsm8k import GSM8K, gsm8k_metric
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | gsm8k = GSM8K()
  // | gsm8k_trainset = gsm8k.train[:10]
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // |
  // | optimizer = dspy.BootstrapFewShot(metric=gsm8k_metric, max_bootstrapped_demos=4, max_labeled_demos=4, max_rounds=5)
  // | compiled_dspy_program = optimizer.compile(dspy_program, trainset=gsm8k_trainset)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 35–37) ────────────────────
  // | compiled_dspy_program.save("./dspy_program/program.json", save_program=False)
  // TODO translate snippet 2

  // ── Snippet 3 (lines 44–46) ────────────────────
  // | compiled_dspy_program.save("./dspy_program/program.pkl", save_program=False)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 50–59) ────────────────────
  // | loaded_dspy_program = dspy.ChainOfThought("question -> answer") # Recreate the same program.
  // | loaded_dspy_program.load("./dspy_program/program.json")
  // |
  // | assert len(compiled_dspy_program.demos) == len(loaded_dspy_program.demos)
  // | for original_demo, loaded_demo in zip(compiled_dspy_program.demos, loaded_dspy_program.demos):
  // |     # Loaded demo is a dict, while the original demo is a dspy.Example.
  // |     assert original_demo.toDict() == loaded_demo
  // | assert str(compiled_dspy_program.signature) == str(loaded_dspy_program.signature)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 66–75) ────────────────────
  // | loaded_dspy_program = dspy.ChainOfThought("question -> answer") # Recreate the same program.
  // | loaded_dspy_program.load("./dspy_program/program.pkl", allow_pickle=True)
  // |
  // | assert len(compiled_dspy_program.demos) == len(loaded_dspy_program.demos)
  // | for original_demo, loaded_demo in zip(compiled_dspy_program.demos, loaded_dspy_program.demos):
  // |     # Loaded demo is a dict, while the original demo is a dspy.Example.
  // |     assert original_demo.toDict() == loaded_demo
  // | assert str(compiled_dspy_program.signature) == str(loaded_dspy_program.signature)
  // TODO translate snippet 5

  // ── Snippet 6 (lines 89–91) ────────────────────
  // | compiled_dspy_program.save("./dspy_program/", save_program=True)
  // TODO translate snippet 6

  // ── Snippet 7 (lines 95–103) ────────────────────
  // | loaded_dspy_program = dspy.load("./dspy_program/")
  // |
  // | assert len(compiled_dspy_program.demos) == len(loaded_dspy_program.demos)
  // | for original_demo, loaded_demo in zip(compiled_dspy_program.demos, loaded_dspy_program.demos):
  // |     # Loaded demo is a dict, while the original demo is a dspy.Example.
  // |     assert original_demo.toDict() == loaded_demo
  // | assert str(compiled_dspy_program.signature) == str(loaded_dspy_program.signature)
  // TODO translate snippet 7

  // ── Snippet 8 (lines 123–135) ────────────────────
  // | import dspy
  // | import my_custom_module
  // |
  // | compiled_dspy_program = dspy.ChainOfThought(my_custom_module.custom_signature)
  // |
  // | # Save the program with the custom module
  // | compiled_dspy_program.save(
  // |     "./dspy_program/",
  // |     save_program=True,
  // |     modules_to_serialize=[my_custom_module]
  // | )
  // TODO translate snippet 8
}
