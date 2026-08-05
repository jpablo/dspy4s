package dspy4s.lm.runtime

import dspy4s.lm.contracts.LmCache

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

object CacheDefaults:
  val defaultDiskDir: Path =
    Option(System.getenv("DSPY4S_CACHEDIR"))
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.home"), ".dspy4s_cache"))

final case class LmCacheConfig(
    enableDiskCache: Boolean = true,
    enableMemoryCache: Boolean = true,
    diskCacheDir: Path = CacheDefaults.defaultDiskDir,
    diskMaxEntries: CacheCapacity = CacheCapacity(200000),
    memoryMaxEntries: CacheCapacity = CacheCapacity(1000000),
    fallbackToMemoryOnDiskFailure: Boolean = true
)

object LmCaches:
  def build(config: LmCacheConfig): LmCache =
    val memory = if config.enableMemoryCache then Some(new InMemoryLmCache(config.memoryMaxEntries)) else None
    val disk   = buildDisk(config)
    CompositeLmCache(memory, disk) match
      case CompositeLmCache(Some(single), None) => single
      case CompositeLmCache(None, Some(single)) => single
      case CompositeLmCache(None, None)         => NoopLmCache
      case composite                            => composite

  private def buildDisk(config: LmCacheConfig): Option[LmCache] =
    if !config.enableDiskCache then None
    else
      try Some(new DiskLmCache(config.diskCacheDir, config.diskMaxEntries))
      catch
        case NonFatal(_) if config.fallbackToMemoryOnDiskFailure => None
        case NonFatal(error)                                     => throw error

object LmCacheRegistry:
  private val activeRef = new AtomicReference[LmCache](LmCaches.build(LmCacheConfig()))

  def current: LmCache = activeRef.get()

  def configure(config: LmCacheConfig): LmCache =
    val cache = LmCaches.build(config)
    activeRef.set(cache)
    cache

  def resetDefault(): Unit =
    activeRef.set(LmCaches.build(LmCacheConfig()))
