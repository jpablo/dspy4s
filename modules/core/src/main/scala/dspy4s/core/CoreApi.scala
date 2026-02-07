package dspy4s.core

import dspy4s.core.signatures.DefaultSignatureParser

object CoreApi:
  val module: String = "dspy4s-core"
  val contractsPhase: String = "phase-1"
  val defaultSignatureParser: DefaultSignatureParser = new DefaultSignatureParser()
