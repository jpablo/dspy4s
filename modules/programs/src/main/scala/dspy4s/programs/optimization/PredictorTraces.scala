package dspy4s.programs.optimization

import dspy4s.core.contracts.{DspyError, DynamicValues, TraceEntry, ValidationError}

/** Finds the trace entries of each optimizable leaf.
  *
  * The rule (UPGRADE-3.2.1-to-3.4.0, decision D2): a predictor writes its [[dspy4s.core.contracts.PredictorKey]] into
  * each trace entry that it records, and the key is equal to the key of the read-only metadata of the leaf
  * ([[OptimizableMetadata.key]]). An entry thus belongs to the leaf with the same key. The rule does not use the
  * position of an entry, so a predictor that runs many times in a loop, or that does not run at all, is not a problem.
  *
  * The key is not an object identity. An optimizer must call [[requireDistinctKeys]] before it runs the program.
  */
private[dspy4s] object PredictorTraces:

  /** Reject a program in which two leaves have the same key. The entries of such leaves cannot be told apart. */
  def requireDistinctKeys(leaves: Vector[IdentifiedOptimizable]): Either[DspyError, Unit] =
    leaves.groupBy(_.metadata.key).values.filter(_.sizeIs > 1).toVector.sortBy(_.head.id).headOption match
      case None        => Right(())
      case Some(twins) =>
        val names = twins.map(leaf => s"'${leaf.displayName}'").mkString(", ")
        val key   = twins.head.metadata.key
        Left(
          ValidationError(
            s"The optimizable leaves $names have the same module name '${key.moduleName}' and the same signature " +
              s"structure '${key.structure.name}', so a trace entry cannot be assigned to one of them. " +
              "Give each predictor a different name, for example `Predict(signature, name = Some(\"...\"))`."
          )
        )

  /** The entries of each leaf in one trace. The result is aligned with `leaves`, and each group keeps the trace order.
    * A leaf that did not run has an empty group. An entry with a key that no leaf has is ignored.
    *
    * A leaf type that records no key is an error: its entries would be lost silently. The check looks for an entry of
    * the same module, with the output fields of the leaf, that has no key and did not run inside a predictor.
    */
  def byLeaf(
      leaves: Vector[IdentifiedOptimizable],
      trace : Vector[TraceEntry]
  ): Either[DspyError, Vector[Vector[TraceEntry]]] =
    val byKey = trace.filter(_.predictor.isDefined).groupBy(_.predictor)
    leaves.foldLeft[Either[DspyError, Vector[Vector[TraceEntry]]]](Right(Vector.empty)) { (groups, leaf) =>
      groups.flatMap { done =>
        val entries = byKey.getOrElse(Some(leaf.metadata.key), Vector.empty)
        if entries.isEmpty && trace.exists(recordedWithoutKey(leaf, _)) then Left(missingKey(leaf))
        else Right(done :+ entries)
      }
    }

  private def recordedWithoutKey(leaf: IdentifiedOptimizable, entry: TraceEntry): Boolean =
    entry.predictor.isEmpty && !entry.withinPredictor && entry.failure.isEmpty &&
      entry.component == leaf.moduleName &&
      DynamicValues.recordKeys(entry.outputs).toSet == leaf.metadata.structure.outputFields.map(_.name).toSet

  private def missingKey(leaf: IdentifiedOptimizable): DspyError =
    ValidationError(
      s"The optimizable leaf '${leaf.displayName}' (module '${leaf.moduleName}') ran, but its trace entry has no " +
        "predictor key, so an optimizer cannot find its calls. The module of a leaf must use " +
        "`ModuleLifecycle.predictor` (or `ModuleLifecycle.dynamicPredictor`) with the key of its metadata."
    )
