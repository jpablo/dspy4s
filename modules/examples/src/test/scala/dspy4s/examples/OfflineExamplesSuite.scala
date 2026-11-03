package dspy4s.examples

import munit.FunSuite

/** Each example that the README marks as "offline" runs to its end with no model. A failure is an exception of the main
  * function.
  */
class OfflineExamplesSuite extends FunSuite:

  private def silently(main: => Unit): Unit =
    Console.withOut(new java.io.ByteArrayOutputStream)(main)

  test("learn.evaluation.dataMain") { silently(learn.evaluation.dataMain()) }
  test("learn.evaluation.metricsMain") { silently(learn.evaluation.metricsMain()) }
  test("deep_dive.data_handling.examplesMain") { silently(deep_dive.data_handling.examplesMain()) }
  test("deep_dive.data_handling.loadingCustomDataMain") { silently(deep_dive.data_handling.loadingCustomDataMain()) }
  test("tutorials.saving.savingMain") { silently(tutorials.saving.savingMain()) }
  test("cheatsheetMain") { silently(cheatsheetMain()) }
  test("signatures.builderMain") { silently(signatures.builderMain()) }
  test("tutorials.talk_to_your_data.tytdSelfCheckMain") { silently(tutorials.talk_to_your_data.tytdSelfCheckMain()) }
