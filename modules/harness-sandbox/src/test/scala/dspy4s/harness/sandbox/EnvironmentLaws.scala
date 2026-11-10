package dspy4s.harness.sandbox

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The laws of the allowlist of variables (5.6). Each value is a fake. */
class EnvironmentLaws extends ScalaCheckSuite:

  private val names = Environment.allowed.toVector ++ Vector(
    "OPENAI_API_KEY",
    "GITHUB_TOKEN",
    "AWS_SECRET_ACCESS_KEY",
    "DB_PASSWORD",
    "MY_PASSWD",
    "GOOGLE_APPLICATION_CREDENTIALS",
    "HTTP_AUTH",
    "api_key",
    "JAVA_HOME",
    "EDITOR",
    "SBT_OPTS",
    "PROJECT_NAME"
  )

  private val parents: Gen[Map[String, String]] =
    Gen.containerOf[Vector, String](Gen.oneOf(names)).map(_.map(name => name -> s"fake-$name").toMap)

  private val extras: Gen[Set[String]] = Gen.containerOf[Set, String](Gen.oneOf(names))

  private def secret(name: String): Boolean =
    Vector("KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH").exists(name.toUpperCase.contains)

  property("Environment: a command gets the names of the allowlist and of the session, with no secret name") {
    forAll(parents, extras) { (parent, extra) =>
      val child = Environment.forChild(parent, extra)
      child.keys.foreach { name =>
        assert(Environment.allowed.contains(name) || extra.contains(name), s"$name is not on a list")
        assert(!secret(name), s"$name is secret")
        assertEquals(child.get(name), parent.get(name))
      }
      parent.keys.filter(n => (Environment.allowed.contains(n) || extra.contains(n)) && !secret(n)).foreach { name =>
        assert(child.contains(name), s"$name is missing")
      }
    }
  }

  property("Environment: more names of the session never remove a name, and a second filter changes nothing") {
    forAll(parents, extras, extras) { (parent, a, b) =>
      val child = Environment.forChild(parent, a)
      assert(child.toSet.subsetOf(Environment.forChild(parent, a ++ b).toSet))
      assertEquals(Environment.forChild(child, a), child)
    }
  }

  test("Environment: a session cannot add a secret name") {
    val parent = Map("PATH" -> "/usr/bin", "OPENAI_API_KEY" -> "fake-key", "PROJECT_NAME" -> "dspy4s")
    assertEquals(
      Environment.forChild(parent, Set("OPENAI_API_KEY", "PROJECT_NAME")),
      Map("PATH" -> "/usr/bin", "PROJECT_NAME" -> "dspy4s")
    )
  }

  test("Environment: the names of the git author go through when the session adds them, and AUTH names do not") {
    // GIT_AUTHOR_NAME holds AUTH in AUTHOR, and it is not a secret.
    val parent = Map(
      "GIT_AUTHOR_NAME"    -> "Fake Name",
      "GIT_AUTHOR_EMAIL"   -> "fake@example.com",
      "GIT_AUTHOR_DATE"    -> "2026-01-01T00:00:00Z",
      "FAKE_AUTH_TOKEN"    -> "fake-1",
      "HTTP_AUTHORIZATION" -> "fake-2"
    )
    assertEquals(
      Environment.forChild(parent, parent.keySet).keySet,
      Set("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE")
    )
  }
