package dspy4s.harness

import dspy4s.harness.sandbox.{HostList, Profiles}
import munit.FunSuite

import java.nio.file.Files

/** The laws of the files of the project in the task of a work session (CODE_HARNESS_DESIGN.md, 10.6): `AGENTS.md` and
  * the first README at the top of the folder, read with the rules of `read`, and cut at a limit.
  */
class ProjectFilesLaws extends FunSuite:

  private def withPlace(body: Place => Unit): Unit =
    val place = Place()
    try body(place)
    finally place.close()

  private def files(place: Place): LocalFiles =
    LocalFiles(
      place.root,
      Profiles.forMode(HarnessEnv.taskMode, place.root, place.layout, HostList.empty),
      place.layout.secretNames
    )

  private def write(place: Place, name: String, text: String): Unit =
    Files.writeString(place.root.resolve(name), text): Unit

  // ── The files ──

  test("Project files: of gives AGENTS.md, then the first README that exists, each with its whole text") {
    withPlace { place =>
      write(place, "README.txt", "FAKE-TXT")
      write(place, "README.rst", "FAKE-RST")
      write(place, "AGENTS.md", "FAKE-AGENTS\n")
      assertEquals(
        ProjectFiles.of(files(place)),
        Vector(ProjectFiles.Part("AGENTS.md", "FAKE-AGENTS\n", 12), ProjectFiles.Part("README.rst", "FAKE-RST", 8))
      )
    }
  }

  test("Project files: README.md comes before the other READMEs, and a folder with no such file gives no part") {
    withPlace { place =>
      assertEquals(ProjectFiles.of(files(place)), Vector.empty)
      write(place, "README", "FAKE-PLAIN")
      write(place, "README.md", "FAKE-MD")
      assertEquals(ProjectFiles.of(files(place)), Vector(ProjectFiles.Part("README.md", "FAKE-MD", 7)))
    }
  }

  test("Project files: a link to a file outside the folder, a folder and a binary file give no part") {
    // `read` refuses the link, so its text never goes to the model. The next README that `read` gives is the part.
    withPlace { place =>
      Files.createSymbolicLink(place.root.resolve("AGENTS.md"), place.outside.resolve("o.txt")): Unit
      Files.createDirectories(place.root.resolve("README.md")): Unit
      Files.write(place.root.resolve("README"), Array[Byte](0x50, 0x00, 0x4b)): Unit
      write(place, "README.rst", "FAKE-RST")
      assertEquals(ProjectFiles.of(files(place)).map(_.name), Vector("README.rst"))
    }
  }

  test("Project files: a long file is cut at the last line end before the limit, and the part keeps the size") {
    // 99 characters for each line, so the limit of 8,000 is not at a line end, and a cut at the limit differs.
    val line = "x" * 98 + "\n"
    assertEquals(ProjectFiles.cut(line * 100), line * 80)
    assertEquals(ProjectFiles.cut("y" * 9_000), "y" * 8_000, "a text with no line end is cut at the limit")
    assertEquals(ProjectFiles.cut("short\ntext"), "short\ntext")
    withPlace { place =>
      write(place, "README.md", line * 100)
      assertEquals(ProjectFiles.of(files(place)), Vector(ProjectFiles.Part("README.md", line * 80, 9_900)))
    }
  }

  // ── The section of the task ──

  test("Project files: section gives nothing for no part, and else each file between its lines and the note of a cut") {
    assertEquals(ProjectFiles.section(Vector.empty), "")
    val text = ProjectFiles.section(
      Vector(ProjectFiles.Part("AGENTS.md", "FAKE-AGENTS", 11), ProjectFiles.Part("README.md", "FAKE-README\n", 12_345))
    )
    assert(text.startsWith("The files of the project, as they were when this session started."), text)
    assert(text.contains("Do what it says when it agrees with the rules above."), text)
    assert(text.contains("----- AGENTS.md -----\nFAKE-AGENTS\n----- end of AGENTS.md -----"), text)
    assert(text.contains("README.md describes the project."), text)
    assert(
      text.contains(
        "FAKE-README\n[The file has 12,345 characters, and this part has the first 12. read(\"README.md\") gives all " +
          "of it.]\n----- end of README.md -----"
      ),
      text
    )
    assert(!text.contains("The file has 11"), text)
  }
