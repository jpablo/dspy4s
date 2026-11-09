package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Access, Mode}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.{Files as JFiles, Path}

/** The laws of the profile (5.4). The profile is pure, so the paths need not exist. */
class ProfileLaws extends ScalaCheckSuite:

  private val scope    = Path.of("/w/scope")
  private val temp     = Path.of("/w/tmp")
  private val journals = Path.of("/w/journals")
  private val secrets  = Vector(Path.of("/w/home/.ssh"), Path.of("/w/home/.aws"))
  private val layout   = Layout(temp, journals, secrets, Layout.secretNames)
  private val hosts    = HostList(Set("example.com", "*.example.org"))

  private val access: Gen[Access] = Gen.oneOf(Access.values.toSeq)

  private val anyModes: Gen[Mode] =
    for
      files    <- access
      shell    <- access
      network  <- access
      llm      <- access
      sessions <- access
      children <- access
      store    <- access
    yield Mode(files, shell, network, llm, sessions, children, store)

  /** Only one mode in nine can start a process, so half of the modes here can. */
  private val modes: Gen[Mode] = Gen.oneOf(anyModes, anyModes.map(_.copy(files = Access.Full, shell = Access.Full)))

  /** Paths in each region of the layout, with a secret name or a .git part in some of them. */
  private val paths: Gen[Path] =
    for
      region <- Gen.oneOf("/w/scope", "/w/scope/.git", "/w/tmp", "/w/journals", "/w/home/.ssh", "/w/home", "/w", "/etc")
      rest   <- Gen.oneOf("", "a", ".env", ".env.local", "b/.env", "x/y", ".git/hooks/pre-commit", ".envrc", "my.env")
    yield Path.of(region).resolve(rest).normalize

  private val hostNames: Gen[String] =
    Gen.oneOf("example.com", "EXAMPLE.com", "a.example.org", "example.org", "other.net", "example.com.", "")

  private val patterns: Gen[Set[String]] =
    Gen.containerOf[Set, String](Gen.oneOf("example.com", "*.example.com", "other.net", "*.net", "EXAMPLE.org"))

  private def profile(mode: Mode): Profile = Profiles.forMode(mode, scope, layout, hosts)

  private def under(path: Path, roots: Path*): Boolean = roots.exists(path.startsWith)

  property("Monotone: if m1 <= m2, the profile of m2 allows what the profile of m1 allows") {
    forAll(modes, modes, paths, hostNames) { (a, b, path, host) =>
      val (low, high) = (profile(a.meet(b)), profile(a))
      assert(!low.canRun || high.canRun)
      assert(!low.canRead(path) || high.canRead(path), s"read $path")
      assert(!low.canWrite(path) || high.canWrite(path), s"write $path")
      assert(!low.canConnect(host) || high.canConnect(host), s"connect $host")
    }
  }

  property("Monotone: a profile that hides more paths allows less, and allows nothing under them") {
    forAll(modes, Gen.containerOf[Vector, Path](paths), paths, hostNames) { (mode, more, path, host) =>
      val (wide, narrow) = (profile(mode), profile(mode).hide(more))
      assert(!narrow.canRead(path) || wide.canRead(path), s"read $path")
      assert(!narrow.canWrite(path) || wide.canWrite(path), s"write $path")
      assertEquals(narrow.canRun, wide.canRun)
      assertEquals(narrow.canConnect(host), wide.canConnect(host))
      if under(path, more*) then assert(!narrow.canRead(path) && !narrow.canWrite(path), s"$path is not hidden")
    }
  }

  property("Authority: a child process needs Shell^ and Files^") {
    forAll(modes) { mode =>
      assertEquals(profile(mode).canRun, mode.shell == Access.Full && mode.files == Access.Full)
    }
  }

  property("Authority: a write is only under the scope or the temporary directory, never in .git or a secret") {
    forAll(modes, paths) { (mode, path) =>
      if profile(mode).canWrite(path) then
        assert(mode.files == Access.Full)
        assert(under(path, scope, temp), s"$path is outside the scope")
        assert(!under(path, scope.resolve(".git")), s"$path is in .git")
        assert(!under(path, (secrets :+ journals)*), s"$path is secret")
    }
  }

  property("Authority: a host is only with Network^ and on the list") {
    forAll(modes, hostNames) { (mode, host) =>
      if profile(mode).canConnect(host) then
        assert(mode.network == Access.Full)
        assert(hosts.allows(host))
    }
  }

  property("Authority: in no mode can a child read a secret or the journals") {
    forAll(modes, paths) { (mode, path) =>
      if under(path, (secrets :+ journals)*) then
        assert(!profile(mode).canRead(path), s"$path is readable")
    }
  }

  test("Authority: local mode writes the scope and reads the other files, with no network") {
    val local = profile(Mode.local)
    assert(local.canWrite(scope.resolve("src/A.scala")))
    assert(local.canWrite(temp.resolve("x")))
    assert(!local.canWrite(Path.of("/etc/hosts")))
    assert(local.canRead(Path.of("/etc/hosts")))
    assert(local.canRead(scope.resolve(".git/config")))
    assert(!local.canWrite(scope.resolve(".git/config")))
    assert(!local.canRead(journals.resolve("j")) && !local.canWrite(journals.resolve("j")))
    assert(!local.canRead(secrets.head.resolve("id_rsa")))
    assert(!local.canConnect("example.com"))
  }

  test("Authority: full mode adds the hosts of the list, and the other modes of 5.2 start no process") {
    assert(profile(Mode.full).canConnect("example.com"))
    assert(profile(Mode.full).canConnect("a.example.org"))
    assert(!profile(Mode.full).canConnect("other.net"))
    Vector(Mode.readOnly, Mode.controller).foreach(mode => assert(!profile(mode).canRun, mode))
    assert(profile(Mode.local.meet(Mode.closed)).canRun, "a child of a local session")
  }

  test("Authority: the layout of a session hides a secret folder and the target of its symbolic link") {
    val place = Place()
    try
      val home      = place.base.resolve("home")
      val elsewhere = JFiles.createDirectories(place.base.resolve("elsewhere"))
      JFiles.createSymbolicLink(home.resolve(".aws"), elsewhere): Unit
      val layout = Layout.forSession(home, place.journals)
      try
        val local = Profiles.forMode(Mode.local, place.scope, layout, HostList.empty)
        assert(JFiles.isDirectory(layout.temp))
        assert(local.canWrite(layout.temp.resolve("x")))
        assert(!local.canRead(home.resolve(".ssh/id_fake")))
        assert(!local.canRead(home.resolve(".aws/credentials")))
        assert(!local.canRead(elsewhere.resolve("credentials")))
      finally JFiles.deleteIfExists(layout.temp): Unit
    finally place.close()
  }

  test("Authority: the profile of a command hides each file with a secret name that exists in a writable root") {
    val place = Place()
    try
      val s = place.scope
      Vector("app/.env.local", "conf/.env/key", ".envrc", "my.env", ".git/.env").foreach { name =>
        val file = s.resolve(name)
        JFiles.createDirectories(file.getParent): Unit
        JFiles.writeString(file, "x"): Unit
      }
      JFiles.writeString(place.temp.resolve(".env.t"), "x"): Unit
      JFiles.createSymbolicLink(s.resolve(".env.link"), place.outside.resolve("r.txt")): Unit
      val command = Profiles.forCommand(Mode.local, s, place.layout, HostList.empty)
      // The files with a secret name, a folder with one and the files in it, and the target of a symbolic link.
      Vector(
        s.resolve(".env"),
        place.odd,
        s.resolve("app/.env.local"),
        s.resolve("conf/.env/key"),
        place.temp.resolve(".env.t"),
        s.resolve(".env.link"),
        place.outside.resolve("r.txt")
      ).foreach(path => assert(!command.canRead(path) && !command.canWrite(path), s"$path is not hidden"))
      // Other names, and .git, which is read-only and which the walk skips.
      Vector(s.resolve(".envrc"), s.resolve("my.env"), s.resolve(".git/.env"))
        .foreach(path => assert(command.canRead(path), s"$path is hidden"))
      // A new file with a secret name holds only data of the command.
      assert(command.canWrite(s.resolve(".env.new")) && command.canWrite(s.resolve("app/.env.production")))
      // A mode with no child process has no writable root, so nothing to walk.
      assertEquals(
        Profiles.forCommand(Mode.readOnly, s, place.layout, HostList.empty).hidden,
        Profiles.forMode(Mode.readOnly, s, place.layout, HostList.empty).hidden
      )
    finally place.close()
  }

  test("Hosts: a pattern names the same host, with no case and no last dot") {
    val list = HostList(Set("Example.com"))
    assert(list.allows("example.COM"))
    assert(list.allows("example.com."))
    assert(!list.allows("a.example.com"))
    assert(!list.allows("example.co"))
  }

  test("Hosts: *.d matches each subdomain of d, and not d") {
    val list = HostList(Set("*.example.com"))
    assert(list.allows("a.example.com"))
    assert(list.allows("b.a.example.com"))
    assert(!list.allows("example.com"))
    assert(!list.allows("aexample.com"))
    assert(!list.allows(".example.com"))
  }

  property("Hosts: an empty list allows no host, and a longer list allows more") {
    forAll(patterns, patterns, hostNames) { (a, b, host) =>
      assert(!HostList.empty.allows(host))
      assert(!HostList(a).allows(host) || HostList(a ++ b).allows(host))
    }
  }
