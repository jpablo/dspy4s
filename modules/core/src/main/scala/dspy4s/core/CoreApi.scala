package dspy4s.core

import dspy4s.core.signatures.DefaultSignatureParser

/** Convenience entry point exposing a shared
  * [[dspy4s.core.signatures.DefaultSignatureParser]] instance.
  *
  * Equivalent to constructing `new DefaultSignatureParser()` yourself;
  * offered so callers that need the parser directly don't have to
  * allocate one. Most code goes through
  * [[dspy4s.core.contracts.SignatureLayout.parse]] (lower-level entry
  * point) or [[dspy4s.typed.Signature.fromString]] (typed wrapper)
  * and never touches this value.
  */
object CoreApi:

  val defaultSignatureParser: DefaultSignatureParser = new DefaultSignatureParser()
