package dspy4s.harness

import java.nio.file.{Files, Path}

/** The laws of the helpers of the tests. */
class SupportLaws extends munit.FunSuite:

  // CI of 2026-11-11: the cleanup of a host law failed, because SQLite removed the -shm file of a session file during
  // the walk of `remove`. Here a second thread removes the files during the walk.
  test("Support: remove ends with no folder, also when another thread removes its files during the walk") {
    (1 to 20).foreach { round =>
      val folder = Files.createTempDirectory("dspy4s-remove-law-")
      val files  = (1 to 2_000).map(i => Files.writeString(folder.resolve(s"f$i.db-shm"), "x"))
      val other  = Thread.ofPlatform().start(() => files.reverse.foreach(f => Files.deleteIfExists(f): Unit))
      try Support.remove(folder)
      finally other.join(10_000)
      assert(!Files.exists(folder), s"round $round: the folder stays")
    }
  }

  test("Support: remove of a folder that does not exist does nothing") {
    Support.remove(Path.of("/nonexistent-dspy4s-folder"))
  }
