package dspy4s.algebra

import dspy4s.algebra.Optic.*
import munit.FunSuite

final class OpticSuite extends FunSuite:

  private final case class Address(street: String)
  private final case class Person(address: Address)

  private val addressLens = new Lens[Person, Address]:
    def get(person: Person): Address                  = person.address
    def set(person: Person, address: Address): Person = person.copy(address = address)

  private val streetLens = new Lens[Address, String]:
    def get(address: Address): String                  = address.street
    def set(address: Address, street: String): Address = address.copy(street = street)

  test("the existential context rebuilds through same-carrier composition") {
    val street = addressLens.andThen(streetLens)
    val alice  = Person(Address("Main"))

    assertEquals(street.to(alice)._2, "Main")
    assertEquals(street.modify(alice)(_.toUpperCase), Person(Address("MAIN")))
  }

  test("Lens remains source compatible and is a Tuple2 optic") {
    val alice = Person(Address("Main"))

    assertEquals(addressLens.get(alice), Address("Main"))
    assertEquals(addressLens.to(alice), alice -> Address("Main"))
    assertEquals(addressLens.from(alice -> Address("Broadway")), Person(Address("Broadway")))
  }

  test("Lens states the modify identity, composition, and set consistency laws") {
    val alice = Person(Address("Main"))

    assertEquals(addressLens.modifyIdentity(alice).lhs, addressLens.modifyIdentity(alice).rhs)
    assertEquals(
      addressLens.modifyComposition(alice, _.copy(street = "First"), _.copy(street = "Second")).lhs,
      addressLens.modifyComposition(alice, _.copy(street = "First"), _.copy(street = "Second")).rhs
    )
    assertEquals(
      addressLens.consistentSetModify(alice, Address("Broadway")).lhs,
      addressLens.consistentSetModify(alice, Address("Broadway")).rhs
    )
  }
