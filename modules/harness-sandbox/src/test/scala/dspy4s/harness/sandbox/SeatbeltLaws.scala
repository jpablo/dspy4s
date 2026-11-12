package dspy4s.harness.sandbox

import dspy4s.harness.guest.Mode

import java.nio.file.Path

/** The escape tests on macOS (5.4). They skip on another host. */
class SeatbeltLaws extends EscapeProbes(Seatbelt, "Seatbelt:", proxies = true):

  test("Seatbelt: the policy of a profile with no child process denies each operation") {
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.readOnly, place.scope, place.layout, HostList.empty)
      assertEquals(Seatbelt.policy(profile, Some(1234)), "(version 1)\n(deny default)\n")
    finally place.close()
  }

  test("Seatbelt: the backend closes names, and the policy closes each secret name in each folder after the allows") {
    // One rule with no walk of the scope (5.4). A walk before each command made each command wait in a large scope.
    assert(Seatbelt.closesNames)
    val layout  = Layout(Path.of("/w/tmp"), Path.of("/w/journals"), Vector.empty, Layout.secretNames)
    val profile = Profiles.forMode(Mode.local, Path.of("/w/scope"), layout, HostList.empty, closesNames = true)
    val policy  = Seatbelt.policy(profile, None)
    val rule    = """(deny file-read* file-write* (regex #"/\.env(\.[^/]*)?(/|$)"))"""
    assert(policy.contains(rule), policy)
    assert(policy.indexOf(rule) > policy.indexOf("(allow file-write*"), policy)
    val odd = Seatbelt.policy(profile.copy(hiddenNames = Set("a+b")), None)
    assert(odd.contains("""(regex #"/a\+b(\.[^/]*)?(/|$)")"""), odd)
  }

  test("Seatbelt: the policy escapes a quote, a backslash, a newline and a return in a path") {
    // A command can make a file with a secret name, and the next policy names it. A name that the policy cannot hold
    // would stop each later command.
    val layout  = Layout(Path.of("/w/tmp"), Path.of("/w/journals"), Vector.empty, Layout.secretNames)
    val profile = Profiles.forMode(Mode.local, Path.of("/w/a\"b\\c\nd\re"), layout, HostList.empty)
    val policy  = Seatbelt.policy(profile, None)
    assert(policy.contains("""(subpath "/w/a\"b\\c\nd\re")"""), policy)
  }
