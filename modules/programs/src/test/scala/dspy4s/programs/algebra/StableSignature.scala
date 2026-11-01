package dspy4s.programs.algebra

import dspy4s.programs.DynamicSignature
import dspy4s.signatures.Signature

/** Alias-safe generic view of a dynamic-signature bundle. Scala widens an ordinary `val alias = s` back to
  * `DynamicSignature`, which gives the alias a new path projection. The type arguments of this view capture the
  * originating path once, then survive any number of ordinary `val` aliases.
  */
final class StableSignature[I, O](underlying: DynamicSignature { type In = I; type Out = O }):
  type In  = I
  type Out = O

  val signature: Signature[I, O] = underlying.signature

extension (bundle: DynamicSignature)
  /** `val same = bundle.stable` gives `same.In = bundle.In`, and likewise for `Out`. */
  def stable: StableSignature[bundle.In, bundle.Out] = new StableSignature(bundle)
