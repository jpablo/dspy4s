package dspy4s.core.contracts

trait ClosableIterator[+A] extends Iterator[A] with AutoCloseable
