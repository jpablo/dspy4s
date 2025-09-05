package dspy

import munit.FunSuite
import dspy.utils.{ConfigLoader, Settings}

import java.nio.file.{Files, Paths}

class SettingsLoaderSuite extends FunSuite {
  private def withTempFile(content: String)(f: java.nio.file.Path => Unit): Unit = {
    val dir  = Files.createTempDirectory("dspy4s-test-")
    val file = dir.resolve("config.json")
    Files.write(file, content.getBytes("UTF-8"))
    try f(file)
    finally Files.deleteIfExists(file)
  }

  test("Settings.load reads config file values") {
    val json = """
      {
        "openai_api_key": "file-key",
        "openai_base_url": "https://example.com/v1",
        "request_timeout_ms": 1234,
        "debug": true,
        "log_prompts": true,
        "log_responses": false
      }
    """.stripMargin

    withTempFile(json) { p =>
      val s = Settings.loadWithEnv(Map.empty, p)
      assertEquals(s.openaiApiKey, Some("file-key"))
      assertEquals(s.openaiBaseUrl, "https://example.com/v1")
      assertEquals(s.requestTimeoutMillis, 1234)
      assertEquals(s.debug, true)
      assertEquals(s.logPrompts, true)
      assertEquals(s.logResponses, false)
    }
  }

  test("Settings.load falls back to defaults when file missing") {
    val missing = Paths.get("/unlikely/path/does/not/exist/config.json")
    val s       = Settings.load(missing)
    assertEquals(s.openaiBaseUrl, "https://api.openai.com/v1")
    assertEquals(s.requestTimeoutMillis, 60000)
  }
}
