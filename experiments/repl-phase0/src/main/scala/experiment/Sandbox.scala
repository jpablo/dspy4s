package experiment

import java.io.File
import java.nio.file.Paths

/** The parent class loader of each REPL, as in atc (`atc.sandbox.Sandbox`). */
object Sandbox:
  private val sharedPrefixes = List("scala.", "harness.guest.")
  private val hiddenPrefixes = List("scala.quoted.runtime.impl.", "scala.tools.")

  def isShared(name: String): Boolean =
    sharedPrefixes.exists(name.startsWith) && !hiddenPrefixes.exists(name.startsWith)

  /** Gives the shared packages from the application loader, and the JDK for all other names.
    *
    * It hides the class resources. In the `true` mode of `-Xrepl-interrupt-instrumentation`, the REPL loader reads the
    * bytecode of a class through its parent and defines an instrumented copy. Without the resource, the REPL loader
    * delegates, so `scala.Console` and `harness.guest.*` stay the same classes as in the host.
    */
  final class SandboxLoader(app: ClassLoader) extends ClassLoader("repl-sandbox", ClassLoader.getPlatformClassLoader):
    override protected def loadClass(name: String, resolve: Boolean): Class[?] =
      if isShared(name) then
        val c = app.loadClass(name)
        if resolve then resolveClass(c)
        c
      else super.loadClass(name, resolve)

    override def getResource(name: String): java.net.URL | Null =
      if name.endsWith(".class") then null else super.getResource(name)

  def newLoader(): ClassLoader = SandboxLoader(classOf[harness.guest.Files].getClassLoader)

  /** The compile classpath of the guest code: the Scala library and the guest API classes. */
  lazy val guestClasspath: String = List(classOf[scala.Predef$], classOf[harness.guest.Files])
    .map(c => Paths.get(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString)
    .distinct
    .mkString(File.pathSeparator)
