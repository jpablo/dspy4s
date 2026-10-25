package dspy4s.core.contracts

import zio.blocks.schema.Reflect

/** Helpers that read a zio-blocks `Reflect`. The [[TypeMarker]] of core and the codec of signatures use them. */
private[dspy4s] object Reflects:

  /** The reflect behind a deferred reflect. zio-blocks defers the reflect of a field with a default value, and of a
    * field whose type can be recursive.
    */
  def withoutDeferred[F[_, _], A](reflect: Reflect[F, A]): Reflect[F, A] =
    reflect match
      case deferred: Reflect.Deferred[F, A] => withoutDeferred(deferred.value)
      case other                            => other

  /** The case names of an enum with no parameters. Empty for each other type. */
  def caseNames(reflect: Reflect[?, ?]): Vector[String] =
    withoutDeferred(reflect) match
      case variant: Reflect.Variant[?, ?] =>
        val cases = variant.cases.toVector
        if cases.forall(term => isEmptyRecord(term.value)) then cases.map(_.name) else Vector.empty
      case _ => Vector.empty

  private def isEmptyRecord(reflect: Reflect[?, ?]): Boolean =
    withoutDeferred(reflect) match
      case record: Reflect.Record[?, ?] => record.fields.isEmpty
      case _                            => false
