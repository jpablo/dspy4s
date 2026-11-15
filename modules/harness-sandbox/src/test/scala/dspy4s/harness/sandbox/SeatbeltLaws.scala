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

  test("Seatbelt: the policy denies each control path after the allows, with a rule on a name where a path needs one") {
    // Choice 19 (5.4): git can change its index and refs, and not the files that run code outside the sandbox.
    val layout  = Layout(Path.of("/w/tmp"), Path.of("/w/journals"), Vector.empty, Layout.secretNames)
    val profile = Profiles.forMode(Mode.local, Path.of("/w/scope"), layout, HostList.empty, closesNames = true)
    val policy  = Seatbelt.policy(profile, None)
    Vector(
      """(deny file-write* (literal "/w/scope/.git"))""",
      """(deny file-write* (subpath "/w/scope/.git/config"))""",
      """(deny file-write* (subpath "/w/scope/.git/hooks"))""",
      """(deny file-write* (subpath "/w/scope/.idea"))""",
      """(deny file-write* (require-all (subpath "/w/scope/.git/modules") """ +
        """(regex #"/(commondir|config|config\.worktree|hooks)(/|$)")))""",
      """(deny file-write* (require-all (subpath "/w/scope/.git/worktrees") """ +
        """(regex #"/(commondir|config\.worktree|gitdir)(/|$)")))""",
      """(deny file-write* (require-all (subpath "/w/scope") (require-not (subpath "/w/scope/.git")) """ +
        """(regex #"/(\.git)(/|$)")))"""
    ).foreach { rule =>
      assert(policy.contains(rule) && policy.indexOf(rule) > policy.indexOf("(allow file-write*"), s"$rule\n$policy")
    }
    // With no rule on names, the profile holds all of .git when it exists, as for bubblewrap.
    val place = Place()
    try
      val all = Seatbelt.policy(Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty), None)
      assert(all.contains(s"""(deny file-write* (subpath "${place.scope.resolve(".git")}"))"""), all)
      assert(!all.contains("require-all"), all)
    finally place.close()
  }

  test("Seatbelt: the policy escapes a quote, a backslash, a newline and a return in a path") {
    // A command can make a file with a secret name, and the next policy names it. A name that the policy cannot hold
    // would stop each later command.
    val layout  = Layout(Path.of("/w/tmp"), Path.of("/w/journals"), Vector.empty, Layout.secretNames)
    val profile = Profiles.forMode(Mode.local, Path.of("/w/a\"b\\c\nd\re"), layout, HostList.empty)
    val policy  = Seatbelt.policy(profile, None)
    assert(policy.contains("""(subpath "/w/a\"b\\c\nd\re")"""), policy)
  }
