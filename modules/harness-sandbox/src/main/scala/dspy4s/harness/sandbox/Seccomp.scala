package dspy4s.harness.sandbox

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files as JFiles, Path}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.Arrays
import scala.util.Try

/** The seccomp filter that bubblewrap loads for each command (CODE_HARNESS_DESIGN.md, 5.4).
  *
  * A read-only mount does not stop a connection to a UNIX socket of the host, and a new network namespace covers only
  * the abstract sockets. The D-Bus bus of the user can start a process outside the sandbox, and docker.sock gives root.
  * So the filter refuses `socket` for `AF_UNIX` with `EPERM`. It also refuses `io_uring_setup`, because io_uring can
  * make a socket with no `socket` call.
  *
  * The filter also refuses `socketpair` for a datagram pair (`SOCK_DGRAM`). A datagram socket of a pair can connect
  * again or send to a path, for example to the journal of the host. A stream pair and a seqpacket pair stay connected
  * to their peer, so they reach no socket of the host, and the filter allows them. The flags `SOCK_CLOEXEC` and
  * `SOCK_NONBLOCK` are above the 4 bits of the type. Each other call is allowed.
  *
  * The filter refuses each call of another ABI, for example a 32-bit one, which can make a socket through `socketcall`.
  * On x86_64 it also refuses each call of the x32 ABI. The filter exists only for aarch64 and x86_64. On another host,
  * bubblewrap does not work, so `exec` does not run a command.
  */
object Seccomp:

  // The opcodes of classic BPF.
  private val Load = 0x20 // BPF_LD | BPF_W | BPF_ABS
  private val And  = 0x54 // BPF_ALU | BPF_AND | BPF_K
  private val Jeq  = 0x15 // BPF_JMP | BPF_JEQ | BPF_K
  private val Jset = 0x45 // BPF_JMP | BPF_JSET | BPF_K
  private val Ret  = 0x06 // BPF_RET | BPF_K

  // The offsets in struct seccomp_data: nr, arch, and the low 32 bits of args[0] and args[1] on a little-endian host.
  private val NrOffset   = 0
  private val ArchOffset = 4
  private val Arg0Offset = 16
  private val Arg1Offset = 24

  private val Allow        = 0x7fff0000     // SECCOMP_RET_ALLOW
  private val Eperm        = 0x00050000 | 1 // SECCOMP_RET_ERRNO | EPERM
  private val AfUnix       = 1
  private val SockDgram    = 2
  private val TypeMask     = 0xf            // SOCK_TYPE_MASK: the type, with no flags
  private val IoUringSetup = 425            // the same number on aarch64 and x86_64
  private val X32Bit       = 0x40000000     // __X32_SYSCALL_BIT

  /** A native ABI: the audit arch, the numbers of `socket` and `socketpair`, and true for x86_64, which has the x32 ABI
    * too.
    */
  private final case class Abi(audit: Int, socket: Int, socketpair: Int, x32: Boolean)

  /** The native ABI of a value of `os.arch`. */
  private def native(arch: String): Option[Abi] =
    arch match
      case "aarch64"          => Some(Abi(0xc00000b7, 198, 199, x32 = false)) // AUDIT_ARCH_AARCH64
      case "amd64" | "x86_64" => Some(Abi(0xc000003e, 41, 53, x32 = true))    // AUDIT_ARCH_X86_64
      case _                  => None

  /** Where a jump goes: the next step, a step `n` steps after the next one, or one of the two returns at the end. */
  private enum Target derives CanEqual:
    case Onward, Allowed, Refused
    case Skip(n: Int)

  private enum Step:
    case Read(offset: Int)
    case Mask(k: Int)
    case Jump(op: Int, k: Int, yes: Target, no: Target)

  /** The program for a value of `os.arch`, as bytes of struct sock_filter, or none for another arch. */
  def program(arch: String): Option[Array[Byte]] =
    native(arch).map { abi =>
      import Step.*, Target.*
      val steps = Vector(Read(ArchOffset), Jump(Jeq, abi.audit, Onward, Refused), Read(NrOffset)) ++
        Option.when(abi.x32)(Jump(Jset, X32Bit, Refused, Onward)) ++
        Vector(
          Jump(Jeq, IoUringSetup, Refused, Onward),
          // socket: past the next two steps when the call is another one.
          Jump(Jeq, abi.socket, Onward, Skip(2)),
          Read(Arg0Offset),
          Jump(Jeq, AfUnix, Refused, Allowed),
          Jump(Jeq, abi.socketpair, Onward, Allowed),
          Read(Arg1Offset),
          Mask(TypeMask),
          Jump(Jeq, SockDgram, Refused, Allowed)
        )
      encode(steps)
    }

  /** The bytes of the steps, then `ret ALLOW` and `ret ERRNO|EPERM`. A jump counts the steps after it. */
  private def encode(steps: Vector[Step]): Array[Byte] =
    val allowAt                                        = steps.size
    val bytes                                          = ByteBuffer.allocate((steps.size + 2) * 8).order(ByteOrder.LITTLE_ENDIAN)
    def put(code: Int, jt: Int, jf: Int, k: Int): Unit =
      bytes.putShort(code.toShort).put(jt.toByte).put(jf.toByte).putInt(k): Unit
    def offset(at: Int, target: Target): Int =
      target match
        case Target.Onward  => 0
        case Target.Skip(n) => n
        case Target.Allowed => allowAt - at - 1
        case Target.Refused => allowAt + 1 - at - 1
    steps.zipWithIndex.foreach {
      case (Step.Read(field), _)           => put(Load, 0, 0, field)
      case (Step.Mask(k), _)               => put(And, 0, 0, k)
      case (Step.Jump(op, k, yes, no), at) => put(op, offset(at, yes), offset(at, no), k)
    }
    put(Ret, 0, 0, Allow)
    put(Ret, 0, 0, Eperm)
    bytes.array

  /** The program of this host. */
  private lazy val bytes: Option[Array[Byte]] = program(System.getProperty("os.arch", ""))

  private var written: Option[Path] = None

  /** A file with the program of this host, or none when no program exists for its arch or the file cannot be written.
    * The harness writes the file once for each JVM, in a new temporary file that only its owner can read, and again
    * when the file is gone or changed, for example after a cleanup of the temporary folder. `Bubblewrap.wrap` refuses a
    * profile that can write the file, so no command can change the filter of a later command.
    */
  def file(): Option[Path] =
    synchronized {
      bytes.flatMap { program =>
        written.filter(path => Try(Arrays.equals(JFiles.readAllBytes(path), program)).getOrElse(false)).orElse {
          val made = Try {
            val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            val path  = JFiles.createTempFile("dspy4s-seccomp-", ".bpf", owner)
            path.toFile.deleteOnExit()
            JFiles.write(path, program): Unit
            Layout.real(path)
          }.toOption
          written = made
          made
        }
      }
    }
