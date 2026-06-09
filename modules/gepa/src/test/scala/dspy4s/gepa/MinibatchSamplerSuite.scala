package dspy4s.gepa

import munit.FunSuite

class MinibatchSamplerSuite extends FunSuite:

  test("EpochShuffled covers every train example once per epoch and pads the final window") {
    val sampler = new MinibatchSampler.EpochShuffled(minibatchSize = 2, seed = 1L)
    val size    = 5 // 5 % 2 != 0 -> padded to 6 (three windows of 2)
    val windows = (0 until 3).map(i => sampler.sample(size, i))

    windows.foreach(w => assertEquals(w.size, 2, s"each window is minibatch-sized: $w"))
    windows.flatten.foreach(idx => assert(idx >= 0 && idx < size, s"index in range: $idx"))
    // One epoch (three windows) is the padded shuffle, so every real id 0..4 appears, length 6 (one pad repeat).
    val epoch0 = windows.flatten
    assertEquals(epoch0.toSet, Set(0, 1, 2, 3, 4))
    assertEquals(epoch0.size, 6)
  }

  test("EpochShuffled reshuffles per epoch (no example starved across epochs) and walks sequential windows") {
    val sampler = new MinibatchSampler.EpochShuffled(minibatchSize = 2, seed = 3L)
    val size    = 4 // exact multiple -> no padding; two windows per epoch
    val epoch0  = (0 until 2).flatMap(i => sampler.sample(size, i))
    val epoch1  = (2 until 4).flatMap(i => sampler.sample(size, i))
    assertEquals(epoch0.toSet, Set(0, 1, 2, 3), "epoch 0 covers all ids")
    assertEquals(epoch1.toSet, Set(0, 1, 2, 3), "epoch 1 (reshuffled) also covers all ids")
  }

  test("EpochShuffled handles a trainset smaller than the minibatch by padding up") {
    val sampler = new MinibatchSampler.EpochShuffled(minibatchSize = 3, seed = 0L)
    val mb      = sampler.sample(trainsetSize = 1, iteration = 0)
    assertEquals(mb.size, 3)
    assertEquals(mb.toSet, Set(0)) // only id 0 exists; padded to fill the window
  }

  test("EpochShuffled is deterministic for a given seed") {
    def run() =
      val s = new MinibatchSampler.EpochShuffled(2, 7L)
      (0 until 6).map(i => s.sample(5, i)).toVector
    assertEquals(run(), run())
  }

  test("RandomDraw returns minibatch-sized in-range draws") {
    val sampler = new MinibatchSampler.RandomDraw(minibatchSize = 2, seed = 1L)
    val mb      = sampler.sample(trainsetSize = 5, iteration = 0)
    assertEquals(mb.size, 2)
    assertEquals(mb.distinct.size, 2, "a single draw is without replacement")
    mb.foreach(idx => assert(idx >= 0 && idx < 5))
  }

  test("MinibatchSampler.of selects the configured strategy") {
    assert(MinibatchSampler.of(BatchSamplerKind.EpochShuffled, 2, 0L).isInstanceOf[MinibatchSampler.EpochShuffled])
    assert(MinibatchSampler.of(BatchSamplerKind.RandomDraw, 2, 0L).isInstanceOf[MinibatchSampler.RandomDraw])
  }
