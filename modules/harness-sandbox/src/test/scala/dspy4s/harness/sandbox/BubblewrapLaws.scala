package dspy4s.harness.sandbox

/** The escape tests on Linux (5.4). They skip on a host with no working bwrap. The network stays closed in each mode,
  * so the proxy probe expects no answer.
  */
class BubblewrapLaws extends EscapeProbes(Bubblewrap, "Bubblewrap:", proxies = false)
