package dspy4s.harness.sandbox

/** The escape tests on Linux (5.4). They skip on a host with no working bwrap. The network stays closed in each mode,
  * so the proxy probe expects no answer.
  */
class BubblewrapLaws extends EscapeProbes(Bubblewrap, "Bubblewrap:", proxies = false):

  test("Bubblewrap: a profile that can write the seccomp filter gets no command line") {
    // A command could change the filter of a later command.
    assume(Bubblewrap.works(), "bubblewrap does not work on this host")
    val filter  = Seccomp.file().getOrElse(fail("no seccomp filter on this host"))
    val folder  = filter.getParent
    val profile = Profile(
      canRun = true,
      writable = Vector(folder),
      readOnly = Vector.empty,
      hidden = Vector.empty,
      net = Net.Closed
    )
    intercept[IllegalStateException](Bubblewrap.wrap(profile, folder, Vector("/bin/sh", "-c", "true"), None)): Unit
  }
