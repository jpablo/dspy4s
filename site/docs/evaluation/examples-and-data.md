# Examples & data

An `Example` is a single labeled data point: a set of named fields, with some of
them marked as inputs. Examples are the unit of data that metrics score and
optimizers learn from.

## Building an example

```scala
--8<-- "learn/evaluation/Data.scala:example-basic"
```

## Inputs and labels

An example holds both the inputs a program receives and the labels it should
produce. Mark which fields are inputs with `withInputs`; the rest are treated as
labels. `inputs` and `labels` split a row accordingly:

```scala
--8<-- "learn/evaluation/Data.scala:inputs-labels"
```

This split is what lets a metric compare a program's output against the expected
labels.

## Datasets

A dataset is just a `Vector[Example]`. Build one directly and slice it with
ordinary Scala (`xs.take(75)`, `xs.drop(75)`) to make train and dev splits:

```scala
--8<-- "learn/evaluation/Data.scala:trainset"
```

Next: [Metrics](metrics.md).
