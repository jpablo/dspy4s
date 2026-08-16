package dspy4s.bench

import dspy4s.algebra.{Lens, Optic}
import dspy4s.algebra.Optic.*
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class OpticBench:
  final case class Address(street: String)
  final case class Person(address: Address)

  private val address = new Lens[Person, Address]:
    def get(person: Person): Address                  = person.address
    def set(person: Person, value: Address): Person   = person.copy(address = value)

  private val street = new Lens[Address, String]:
    def get(value: Address): String                   = value.street
    def set(value: Address, replacement: String): Address = value.copy(street = replacement)

  private val composed: Optic[Person, Person, String, String, Tuple2] = address.andThen(street)
  private val source = Person(Address("main"))

  @Benchmark def existentialOpticModify(): Person =
    composed.modify(source)(_.toUpperCase)

  @Benchmark def directCopyModify(): Person =
    source.copy(address = source.address.copy(street = source.address.street.toUpperCase))
