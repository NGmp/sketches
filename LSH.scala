package atrox.sketch

import atrox.{ fastSparse, IntFreqMap, Bits }
import java.lang.System.arraycopy
import java.lang.Long.{ bitCount, rotateLeft }
import java.util.Arrays
import scala.util.hashing.MurmurHash3
import scala.collection.{ mutable, GenSeq }



case class LSHBuildCfg(
  /** every bucket that has more than this number of elements is discarded */
  maxBucketSize: Int = Int.MaxValue,

  /** Determine how many bands are computed during one pass over data.
    * Multiple band groups can be execute in parellel.
    *
    * Increasing this number may lower available parallelisms. On the other
    * hand it increases locality or reference which may lead to better
    * performance but need to instantiate all sketchers and those might be
    * rather big (in case of RandomHyperplanes and RandomProjections)
    *
    * 1         - bands are computed one after other in multiple passes,
    *             if computeBandsInParallel is set, all of them are compute in parallel.
    * lsh.bands - all bands are computed in one pass over data
    *             This setting needs to instantiate all sketchers at once, but
    *             it's necessary when data source behave like an iterator.
    */
  bandsInOnePass: Int = 1,

  /** If this is set to true, program does one prior pass to count number of
    * values associated with every hash. It needs to do more work but alocates
    * only necessary amount of memory. */
  collectCounts: Boolean = false,

  computeBandsInParallel: Boolean = false
)

case class LSHCfg(
  maxBucketSize: Int = Int.MaxValue,

  /** How many candidates to select. */
  maxCandidates: Int = Int.MaxValue,

  /** How many final result to return. */
  maxResults: Int = Int.MaxValue
) {
  def accept(idxs: Array[Int]) = idxs != null && idxs.length <= maxBucketSize
}


case class Sim(a: Int, b: Int, estimatedSimilatity: Double, similarity: Double)


object LSH {


  // === BitLSH =================

  def apply(sk: BitSketching, bands: Int): BitLSH =
    apply(sk, bands, false, LSHBuildCfg())

  def apply(sk: BitSketching, bands: Int, includeSketches: Boolean, cfg: LSHBuildCfg): BitLSH = {
    require(sk.sketchLength % 64 == 0 && bands > 0)

    val bandBits = sk.sketchLength / bands
    val bandMask = (1L << bandBits) - 1
    val bandSize = (1 << bandBits)
    val longsLen = sk.sketchLength / 64

    require(bandBits < 32)

    if (includeSketches) ??? // TODO

    val idxs     = new Array[Array[Int]](bands * bandSize)
    val sketches = null//new Array[Array[Long]](bands * bandSize)

    for ((bs, bandGroup) <- bandGrouping(bands, cfg)) {

      val skslices = bs map { b => sk.slice(b * bandBits, (b+1) * bandBits) } toArray

      def runItems(f: (Int, Int) => Unit) = {
        val base = bs(0)
        val end = bs.last
        var i = 0 ; while (i < sk.length) {
          var b = base ; while (b <= end) {
            val h = skslices(b-base).getSketchFragmentAsLong(i, 0, bandBits).toInt
            assert(h <= bandSize)
            f((b - base) * bandSize + h, i)
            b += 1
          }
          i += 1
        }
      }

      val bandMap = if (cfg.collectCounts) {
        val counts = new Array[Int](cfg.bandsInOnePass * bandSize)
        runItems((h, i) => counts(h) += 1)
        Grouping(cfg.bandsInOnePass * bandSize, Int.MaxValue, counts) // Grouping.Counted
      } else {
        Grouping(cfg.bandsInOnePass * bandSize, cfg.bandsInOnePass * sk.length) // Grouping.Sorted
      }

      runItems((h, i) => bandMap.add(h, i))

      bandMap.getAll foreach { case (h, is) =>
        require(fastSparse.isDistinctIncreasingArray(is))
        idxs(bandGroup * bandSize * cfg.bandsInOnePass + h) = is
      }
    }

    val _sk = sk match { case sk: BitSketch => sk ; case _ => null }
    new BitLSH(_sk, sk.estimator, LSHCfg(), idxs, sketches, sk.sketchLength, bands, bandBits, includeSketches)
  }


  def ripBits(sketchArray: Array[Long], bitsPerSketch: Int, i: Int, band: Int, bandBits: Int): Int =
    Bits.getBitsOverlapping(sketchArray, bitsPerSketch * i + band * bandBits, bandBits).toInt


  // === IntLSH =================

  def apply(sk: IntSketching, bands: Int): IntLSH =
    apply(sk, bands, 32 - Integer.numberOfLeadingZeros(sk.length), false, LSHBuildCfg())

  def apply(sk: IntSketching, bands: Int, hashBits: Int): IntLSH =
    apply(sk, bands, hashBits, false, LSHBuildCfg())

  def apply(sk: IntSketching, bands: Int, hashBits: Int, includeSketches: Boolean): IntLSH =
    apply(sk, bands, hashBits, includeSketches, LSHBuildCfg())

  def apply(sk: IntSketching, bands: Int, hashBits: Int, includeSketches: Boolean, cfg: LSHBuildCfg): IntLSH = {
    require(bands > 0 && hashBits > 0)

    val bandElements = sk.sketchLength / bands // how many elements from sketch is used in one band
    val hashMask = (1 << hashBits) - 1
    val bandSize = (1 << hashBits) // how many hashes are possible in one band

    if (includeSketches) ??? // TODO


    val idxs     = new Array[Array[Int]](bands * bandSize)
    val sketches = null//new Array[Array[Long]](bands * bandSize)

    for ((bs, bandGroup) <- bandGrouping(bands, cfg)) {

      val scratchpads = Array.ofDim[Int](cfg.bandsInOnePass, bandElements)
      val skslices    = bs map { b => sk.slice(b * bandElements, (b+1) * bandElements) } toArray

      def runItems(f: (Int, Int) => Unit) = {
        val base = bs(0)
        val end = bs.last
        var i = 0 ; while (i < sk.length) {
          var b = base ; while (b <= end) {
            val h = if (sk.isInstanceOf[IntSketch]) {
              // if sk is IntSketch instance directly access it's internal sketch array, this saves some copying
              val _sk = sk.asInstanceOf[IntSketch]
              hashSlice(_sk.sketchArray, _sk.sketchLength, i, b, bandElements, hashBits)
            } else {
              skslices(b-base).writeSketchFragment(i, scratchpads(b), 0)
              hashSlice(scratchpads(b), bandElements, 0, 0, bandElements, hashBits)
            }
            f((b - base) * bandSize + h, i)
            b += 1
          }
          i += 1
        }
      }

      val bandMap = if (cfg.collectCounts) {
        val counts = new Array[Int](cfg.bandsInOnePass * bandSize)
        runItems((h, i) => counts(h) += 1)
        Grouping(cfg.bandsInOnePass * bandSize, Int.MaxValue, counts) // Grouping.Counted
      } else {
        Grouping(cfg.bandsInOnePass * bandSize, cfg.bandsInOnePass * sk.length) // Grouping.Sorted
      }

      runItems((h, i) => bandMap.add(h, i))

      bandMap.getAll foreach { case (h, is) =>
        require(fastSparse.isDistinctIncreasingArray(is))
        idxs(bandGroup * bandSize * cfg.bandsInOnePass + h) = is
      }
    }

    val _sk = sk match { case sk: IntSketch => sk ; case _ => null }
    new IntLSH(_sk, sk.estimator, LSHCfg(), idxs, sketches, sk.sketchLength, bands, bandElements, hashBits, includeSketches)
  }

  def hashSlice(sketchArray: Array[Int], sketchLength: Int, i: Int, band: Int, bandLength: Int, hashBits: Int) = {
    val start = i * sketchLength + band * bandLength
    val end   = start + bandLength
    _hashSlice(sketchArray, start, end) & ((1 << hashBits) - 1)
  }


  /** based on scala.util.hashing.MurmurHash3.arrayHash */
  private def _hashSlice(arr: Array[Int], start: Int, end: Int): Int = {
    var h = MurmurHash3.arraySeed
    var i = start
    while (i < end) {
      h = MurmurHash3.mix(h, arr(i))
      i += 1
    }
    MurmurHash3.finalizeHash(h, end-start)
  }

  // band groups -> (band indexes, band group index)
  def bandGrouping(bands: Int, cfg: LSHBuildCfg): GenSeq[(Seq[Int], Int)] = {
    val bss = (0 until bands grouped cfg.bandsInOnePass).zipWithIndex.toSeq ;
    if (!cfg.computeBandsInParallel) bss else bss.par
  }

}



/** LSH configuration
  *
  * LSH, full Sketch
  * LSH, empty Sketch, embedded sketches
  *  - embedded sketches greatly increase memory usage but can make search
  *    faster because they provide better locality
  * LSH, empty Sketch, no embedded sketches
  *  - can only provide list of candidates, cannot estimate similarities
  *
  * methods with raw prefix might return duplicates
  * methods withount raw prefix must never return duplicates
  *
  * idxs must be sorted
  */
abstract class LSH { self =>
  type SketchArray
  type Sketching <: atrox.sketch.Sketching[T] forSome { type T <: SketchArray}
  type Sketch <: atrox.sketch.Sketch[T] forSome { type T <: SketchArray}
  type Idxs = Array[Int]
  type SimFun = (Int, Int) => Double

  def sketch: Sketch
  def estimator: Estimator[SketchArray]
  def cfg: LSHCfg
  def bands: Int
  def includeSketches: Boolean

  protected def getSketchArray: SketchArray = if (sketch != null) sketch.sketchArray else null.asInstanceOf[SketchArray]

  def withConfig(cfg: LSHCfg)

  def rawStream: Iterator[(Idxs, SketchArray)]
  def rawStreamIndexes: Iterator[Idxs]

  def rawCandidates(sketch: SketchArray, idx: Int): Array[(Idxs, SketchArray)]
  def rawCandidates(sketch: Sketching, idx: Int): Array[(Idxs, SketchArray)] = rawCandidates(sketch.getSketchFragment(idx), 0)
  def rawCandidates(idx: Int): Array[(Idxs, SketchArray)] = rawCandidates(sketch.sketchArray, idx)

  def rawCandidateIndexes(sketch: SketchArray, idx: Int): Array[Idxs]
  def rawCandidateIndexes(sketch: Sketching, idx: Int): Array[Idxs] = rawCandidateIndexes(sketch.getSketchFragment(idx), 0)
  def rawCandidateIndexes(idx: Int): Array[Idxs] = rawCandidateIndexes(sketch.sketchArray, idx)

  def candidateIndexes(sketch: SketchArray, idx: Int): Idxs = fastSparse.union(rawCandidateIndexes(sketch, idx))
  def candidateIndexes(sketch: Sketching, idx: Int): Idxs = candidateIndexes(sketch.getSketchFragment(idx), 0)
  def candidateIndexes(idx: Int): Idxs = candidateIndexes(sketch.sketchArray, idx)

  // those methods need valid sketch object or inline sketches
  // If minEst is set to Double.NegativeInfinity, no estimates are computed.
  // Instead candidates are directly filtered through similarity function.
  def rawSimilarIndexes(sketch: SketchArray, idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs = {
    val minBits = estimator.minSameBits(minEst)
    val res = new collection.mutable.ArrayBuilder.ofInt

    if (includeSketches) {

      for ((idxs, skArr) <- rawCandidates(sketch, idx)) {
        var i = 0
        while (i < idxs.length) {
          val bits = estimator.sameBits(skArr, i, sketch, idx)
          if (bits >= minBits) {
            if (f == null || f(idxs(i), idx) >= minSim) {
              res += idxs(i)
            }
          }
          i += 1
        }
      }

    } else {

      for ((idxs, _) <- rawCandidates(sketch, idx)) {
        var i = 0
        while (i < idxs.length) {
          val bits = estimator.sameBits(sketch, idxs(i), sketch, idx)
          if (bits >= minBits) {
            if (f == null || f(idxs(i), idx) >= minSim) {
              res += idxs(i)
            }
          }
          i += 1
        }
      }

    }

    res.result
  }
  def rawSimilarIndexes(sketch: SketchArray, idx: Int, minEst: Double): Idxs =
    rawSimilarIndexes(sketch, idx, minEst, 0.0, null)
  def rawSimilarIndexes(sketch: Sketching, idx: Int, minEst: Double): Idxs =
    rawSimilarIndexes(sketch.getSketchFragment(idx), 0, minEst, 0.0, null)
  def rawSimilarIndexes(sketch: Sketching, idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs =
    rawSimilarIndexes(sketch.getSketchFragment(idx), 0, minEst, minSim, f)
  def rawSimilarIndexes(idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs =
    rawSimilarIndexes(getSketchArray, idx, minEst, minSim, f)
  def rawSimilarIndexes(idx: Int, minEst: Double): Idxs =
    rawSimilarIndexes(getSketchArray, idx, minEst, 0.0, null)

  // those methods need valid sketch object or inline sketches
  //def similarIndexes(sketch: SketchArray, idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs
  def similarIndexes(sketch: SketchArray, idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs = {
    if (includeSketches && getSketchArray == null) {
      rawSimilarIndexes(sketch, idx, minEst, minSim, f).distinct // TODO filtering
    } else {

      val minBits = estimator.minSameBits(minEst)
      val candidates = selectCandidates(idx)
      val res = new collection.mutable.ArrayBuilder.ofInt

      var i = 0 ; while (i < candidates.length) {
        val bits = estimator.sameBits(sketch, candidates(i), sketch, idx)
        if (bits >= minBits) {
          if (f == null || f(candidates(i), idx) >= minSim) {
            if (idx != candidates(i)) {
              res += candidates(i)
            }
          }
        }
        i += 1
      }

      res.result
    }
  }
  def similarIndexes(sketch: SketchArray, idx: Int, minEst: Double): Idxs =
    similarIndexes(sketch, idx, minEst, 0.0, null)
  def similarIndexes(sketch: Sketching, idx: Int, minEst: Double): Idxs =
    similarIndexes(sketch.getSketchFragment(idx), 0, minEst, 0.0, null)
  def similarIndexes(sketch: Sketching, idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs =
    similarIndexes(sketch.getSketchFragment(idx), 0, minEst, minSim, f)
  def similarIndexes(idx: Int, minEst: Double, minSim: Double, f: SimFun): Idxs =
    similarIndexes(getSketchArray, idx, minEst, minSim, f)
  def similarIndexes(idx: Int, minEst: Double): Idxs =
    similarIndexes(getSketchArray, idx, minEst, 0.0, null)

  // those methods need valid sketch object or inline sketches
  //def similarItems(sketch: SketchArray, idx: Int, minEst: Double, minSim: Double, f: SimFun): Iterator[Sim]
  def similarItems(sketch: SketchArray, idx: Int, minEst: Double, minSim: Double, f: SimFun): Iterator[Sim] = {
    if (includeSketches) {
      rawSimilarIndexes(sketch, idx, minEst, minSim, f).distinct.iterator // TODO filtering
      ???
    } else {
      val minBits = estimator.minSameBits(minEst)
      val candidates = selectCandidates(idx)
      val res = new collection.mutable.ArrayBuffer[Sim]

      var i = 0 ; while (i < candidates.length) {
        val bits = estimator.sameBits(sketch, candidates(i), sketch, idx)
        if (bits >= minBits) {
          var sim: Double = 0.0
          if (f == null || { sim = f(candidates(i), idx) ; sim >= minSim }) {
            res += Sim(candidates(i), idx, estimator.estimateSimilarity(bits), sim)
          }
        }
        i += 1
      }

      res.iterator
    }
  }
  def similarItems(sketch: SketchArray, idx: Int, minEst: Double): Iterator[Sim] =
    similarItems(sketch, idx, minEst, 0.0, null)
  def similarItems(sketch: Sketching, idx: Int, minEst: Double): Iterator[Sim] =
    similarItems(sketch.getSketchFragment(idx), 0, minEst, 0.0, null)
  def similarItems(sketch: Sketching, idx: Int, minEst: Double, minSim: Double, f: SimFun): Iterator[Sim] =
    similarItems(sketch.getSketchFragment(idx), 0, minEst, minSim, f)
  def similarItems(idx: Int, minEst: Double, minSim: Double, f: SimFun): Iterator[Sim] =
    similarItems(getSketchArray, idx, minEst, minSim, f)
  def similarItems(idx: Int, minEst: Double): Iterator[Sim] =
    similarItems(getSketchArray, idx, minEst, 0.0, null)

  def allSimilarIndexes(minEst: Double, minSim: Double, f: SimFun): Iterator[Idxs] =
    Iterator.tabulate(sketch.length) { idx => similarIndexes(idx, minEst, minSim, f) }
  def allSimilarIndexes(minEst: Double): Iterator[Idxs] =
    Iterator.tabulate(sketch.length) { idx => similarIndexes(idx, minEst, 0.0, null) }

  def allSimilarItems(minEst: Double, minSim: Double, f: SimFun): Iterator[(Int, Iterator[Sim])] =
    Iterator.tabulate(sketch.length) { idx => (idx, similarItems(idx, minEst, minSim, f)) }
  def allSimilarItems(minEst: Double): Iterator[(Int, Iterator[Sim])] =
    Iterator.tabulate(sketch.length) { idx => (idx, similarItems(idx, minEst, 0.0, null)) }

  //def similarItemsStream(minEst: Double, minSim: Double, f: SimFun): Iterator[Sim]
  def similarItemsStream(minEst: Double, minSim: Double, f: SimFun): Iterator[Sim] = {
    val minBits = sketch.minSameBits(minEst)

    if (includeSketches) {
      (for ((idxs, skArr) <- rawStream) yield {
        val res = collection.mutable.ArrayBuffer[Sim]()
        var i = 0
        while (i < idxs.length) {
          var j = i+1
          while (j < idxs.length) {
            val bits = estimator.sameBits(skArr, i, skArr, j)
            if (bits >= minBits) {
              var sim: Double = 0.0
              if (f == null || { sim = f(idxs(i), idxs(j)) ; sim >= minSim }) {
                res += Sim(idxs(i), idxs(j), estimator.estimateSimilarity(bits), sim)
              }
            }
            j += 1
          }
          i += 1
        }
        res
      }).flatten

    } else {
      val skarr = getSketchArray

      (for (idxs <- rawStreamIndexes) yield {
        val res = collection.mutable.ArrayBuffer[Sim]()
        var i = 0
        while (i < idxs.length) {
          var j = i+1
          while (j < idxs.length) {
            val bits = estimator.sameBits(skarr, idxs(i), skarr, idxs(j))
            if (bits >= minBits) {
              var sim: Double = 0.0
              if (f == null || { sim = f(idxs(i), idxs(j)) ; sim >= minSim }) {
                res += Sim(idxs(i), idxs(j), estimator.estimateSimilarity(bits), sim)
              }
            }
            j += 1
          }
          i += 1
        }
        res
      }).flatten
    }
  }
  def similarItemsStream(minEst: Double): Iterator[Sim] = similarItemsStream(minEst, 0.0, null)

  def selectCandidates(idx: Int): Idxs =
    if (cfg.maxCandidates == Int.MaxValue) {
      candidateIndexes(getSketchArray, idx)
    } else {
      val map = new IntFreqMap(initialSize = 32, loadFactor = 0.3, freqThreshold = bands)
      for (is <- rawCandidateIndexes(getSketchArray, idx)) { map ++= (is, 1) }
      map.topK(cfg.maxCandidates)
    }

}




/** bandLengh - how many elements in one band
  * hashBits  - how many bits of a hash is used (2^hashBits should be rougly equal to number of items)
  */
final case class IntLSH(
    sketch: IntSketch, estimator: IntEstimator, cfg: LSHCfg,
    idxs: Array[Array[Int]], sketches: Array[Array[Int]],
    sketchLength: Int, bands: Int, bandLength: Int, hashBits: Int,
    includeSketches: Boolean
  ) extends LSH with Serializable {

  type SketchArray = Array[Int]
  type Sketching = IntSketching
  type Sketch = IntSketch

  if (sketches != null) {
    require(idxs.length == sketches.length)
  }

  def withConfig(newCfg: LSHCfg) = copy(cfg = newCfg)

  def bandHashes(sketchArray: SketchArray, idx: Int): Iterator[Int] =
    Iterator.tabulate(bands) { b => bandHash(sketchArray, idx, b) }

  def bandHash(sketchArray: SketchArray, idx: Int, band: Int): Int =
    LSH.hashSlice(sketchArray, sketch.sketchLength, idx, band, bandLength, hashBits)

  def rawStream: Iterator[(Idxs, SketchArray)] = (idxs.iterator zip sketches.iterator).filter(_._1 != null)
  def rawStreamIndexes: Iterator[Idxs] = idxs.iterator filter (_ != null)


  def rawCandidates(sketch: SketchArray, idx: Int): Array[(Idxs, SketchArray)] =
    Array.tabulate(bands) { b =>
      val h = LSH.hashSlice(sketch, sketchLength, idx, b, bandLength, hashBits)
      val bucket = b * (1 << hashBits) + h
      (idxs(bucket), if (sketches == null) null else sketches(bucket))
    }.filter { case (idxs, _) => cfg.accept(idxs) }


  def rawCandidateIndexes(sketch: SketchArray, idx: Int): Array[Idxs] =
    Array.tabulate(bands) { b =>
      val h = LSH.hashSlice(sketch, sketchLength, idx, b, bandLength, hashBits)
      val bucket = b * (1 << hashBits) + h
      idxs(bucket)
    }.filter { idxs => cfg.accept(idxs) }


}




final case class BitLSH(
    sketch: BitSketch, estimator: BitEstimator, cfg: LSHCfg,
    idxs: Array[Array[Int]], sketches: Array[Array[Long]],
    bitsPerSketch: Int, bands: Int, bandBits: Int,
    includeSketches: Boolean
  ) extends LSH with Serializable {

  type SketchArray = Array[Long]
  type Sketching = BitSketching
  type Sketch = BitSketch

  if (sketches != null) {
    require(idxs.length == sketches.length)
  }

  def withConfig(newCfg: LSHCfg) = copy(cfg = newCfg)

  def bandHashes(sketchArray: Array[Long], idx: Int): Iterator[Int] =
    Iterator.tabulate(bands) { b => LSH.ripBits(sketchArray, bitsPerSketch, idx, b, bandBits) }

  def rawStream: Iterator[(Idxs, SketchArray)] = (idxs.iterator zip sketches.iterator).filter(_._1 != null)
  def rawStreamIndexes: Iterator[Idxs] = idxs.iterator filter (_ != null)


  def rawCandidates(sketch: SketchArray, idx: Int): Array[(Idxs, SketchArray)] =
    Array.tabulate(bands) { b =>
      val h = LSH.ripBits(sketch, bitsPerSketch, idx, b, bandBits)
      val bucket = b * (1 << bandBits) + h
      (idxs(bucket), if (sketches == null) null else sketches(bucket))
    }.filter { case (idxs, _) => cfg.accept(idxs) }


  def rawCandidateIndexes(sketch: SketchArray, idx: Int): Array[Idxs] =
    Array.tabulate(bands) { b =>
      val h = LSH.ripBits(sketch, bitsPerSketch, idx, b, bandBits)
      val bucket = b * (1 << bandBits) + h
      idxs(bucket)
    }.filter { idxs => cfg.accept(idxs) }
}
