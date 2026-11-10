package dspy4s.harness.sandbox

import munit.FunSuite

import java.nio.{ByteBuffer, ByteOrder}

/** The laws of the seccomp filter of bubblewrap (5.4), on a small model of classic BPF. The escape tests on Linux run
  * the real filter in the kernel. This model also runs on macOS, so the mutants of the filter die there too.
  */
class SeccompLaws extends FunSuite:

  private val Allow = 0x7fff0000
  private val Eperm = 0x00050001

  /** The verdict of `program` for one call: the number of the call, the audit arch, and the low 32 bits of args[0] and
    * args[1].
    */
  private def verdict(program: Array[Byte], nr: Int, arch: Int, arg0: Int, arg1: Int = 0): Int =
    val code = ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN)
    val data = ByteBuffer
      .allocate(64)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(0, nr)
      .putInt(4, arch)
      .putInt(16, arg0)
      .putInt(24, arg1)
    var pc     = 0
    var a      = 0
    var result = Option.empty[Int]
    while result.isEmpty do
      assert(pc >= 0 && pc * 8 < program.length, s"the program jumps out at $pc")
      val (op, jt, jf, k) = (
        code.getShort(pc * 8) & 0xffff,
        code.get(pc * 8 + 2) & 0xff,
        code.get(pc * 8 + 3) & 0xff,
        code.getInt(pc * 8 + 4)
      )
      op match
        case 0x20 =>
          assert(k % 4 == 0 && k < 64, s"a load at $k")
          a = data.getInt(k)
          pc += 1
        case 0x15 => pc += 1 + (if a == k then jt else jf)
        case 0x45 => pc += 1 + (if (a & k) != 0 then jt else jf)
        case 0x54 =>
          a = a & k
          pc += 1
        case 0x06 => result = Some(k)
        case _    => fail(s"the opcode $op is not in the model")
    result.getOrElse(Eperm)

  /** The native ABIs: the value of `os.arch`, the audit arch, and the numbers of socket, socketpair and read. */
  private val natives = Vector(
    ("aarch64", 0xc00000b7, 198, 199, 63),
    ("amd64", 0xc000003e, 41, 53, 0),
    ("x86_64", 0xc000003e, 41, 53, 0)
  )

  test("Seccomp: socket for AF_UNIX and io_uring_setup get EPERM, and the other calls are allowed") {
    natives.foreach { (name, arch, socket, socketpair, read) =>
      val program = Seccomp.program(name).getOrElse(fail(s"no program for $name"))
      assertEquals(verdict(program, socket, arch, 1), Eperm, s"$name: socket(AF_UNIX)")
      assertEquals(verdict(program, socket, arch, 0x7fffff01), Allow, s"$name: only the domain 1 is AF_UNIX")
      assertEquals(verdict(program, socket, arch, 2), Allow, s"$name: socket(AF_INET)")
      assertEquals(verdict(program, socket, arch, 10), Allow, s"$name: socket(AF_INET6)")
      assertEquals(verdict(program, 425, arch, 0), Eperm, s"$name: io_uring_setup")
      assertEquals(verdict(program, socketpair, arch, 1), Allow, s"$name: socketpair(AF_UNIX)")
      assertEquals(verdict(program, read, arch, 1), Allow, s"$name: read")
    }
  }

  test("Seccomp: socketpair for SOCK_DGRAM gets EPERM, and socketpair for a stream or a packet stream is allowed") {
    // A datagram socket of a pair can connect again or send to a path, for example the journal of the host. A stream
    // pair is connected for good, so it reaches no socket of the host.
    natives.foreach { (name, arch, _, socketpair, _) =>
      val program = Seccomp.program(name).getOrElse(fail(s"no program for $name"))
      Vector(2, 2 | 0x80000, 2 | 0x800).foreach { kind =>
        assertEquals(verdict(program, socketpair, arch, 1, kind), Eperm, s"$name: socketpair(AF_UNIX, $kind)")
      }
      Vector(1, 5, 1 | 0x80000, 5 | 0x800).foreach { kind =>
        assertEquals(verdict(program, socketpair, arch, 1, kind), Allow, s"$name: socketpair(AF_UNIX, $kind)")
      }
    }
  }

  test("Seccomp: each call of another ABI gets EPERM, also the x32 ABI on x86_64") {
    natives.foreach { (name, arch, socket, _, read) =>
      val program = Seccomp.program(name).getOrElse(fail(s"no program for $name"))
      // AUDIT_ARCH_I386 and AUDIT_ARCH_ARM: a 32-bit process can make a socket through socketcall.
      Vector(0x40000003, 0x40000028, arch ^ 1).foreach { other =>
        Vector(read, socket, 102).foreach(nr =>
          assertEquals(verdict(program, nr, other, 2), Eperm, s"$name: $other $nr")
        )
      }
    }
    val x86 = Seccomp.program("amd64").getOrElse(fail("no program for amd64"))
    Vector(0, 41, 53).foreach(nr => assertEquals(verdict(x86, 0x40000000 | nr, 0xc000003e, 2), Eperm, s"x32 $nr"))
  }

  test("Seccomp: no program for another arch, so bubblewrap does not work there") {
    Vector("x86", "i386", "arm", "riscv64", "ppc64le", "s390x", "").foreach { name =>
      assertEquals(Seccomp.program(name).map(_.length), None, name)
    }
  }
