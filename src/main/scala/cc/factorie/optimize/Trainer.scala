package cc.factorie.optimize

import cc.factorie.app.classify.LogLinearModel
import cc.factorie.la._
import util._
import cc.factorie._
import java.util.concurrent.{ExecutorService, Executors, Callable}
import util._
import util.FastLogging

/**
 * Created by IntelliJ IDEA.
 * User: apassos
 * Date: 10/17/12
 * Time: 7:29 PM
 * To change this template use File | Settings | File Templates.
 */

/** Learns the parameters of a Model by processing the gradients and values from a collection of Examples. */
trait Trainer {
  /** The Model that is being trained. */
//  def weightsSet: WeightsSet
  // TODO Trainer should probably have an overrideable method "newGradient" which we could override to get e.g. dense gradients for Online, sparse for Batch, etc -luke & alex
  /** Use all these Examples once to make progress towards training */
  def processExamples(examples: Iterable[Example]): Unit
  /** Would more training help? */
  def isConverged: Boolean
  /** Repeatedly process the examples until training has converged. */
  def trainFromExamples(examples: Iterable[Example]): Unit = while (!isConverged) processExamples(examples)
}

/** Learns the parameters of a Model by summing the gradients and values of all Examples, 
    and passing them to a GradientOptimizer (such as ConjugateGradient or LBFGS). */
class BatchTrainer(val weightsSet: WeightsSet, val optimizer: GradientOptimizer = new LBFGS with L2Regularization) extends Trainer with FastLogging {
  val gradientAccumulator = new LocalWeightsMapAccumulator(weightsSet.newBlankDense)
  val valueAccumulator = new LocalDoubleAccumulator(0.0)
  // TODO This is sad:  The optimizer determines which of gradient/value/margin it needs, but we don't know here
  // so we create them all, possibly causing the Example to do more work.
  def processExamples(examples: Iterable[Example]): Unit = {
    if (isConverged) return
    gradientAccumulator.tensorSet.zero()
    valueAccumulator.value = 0.0
    val startTime = System.currentTimeMillis
    examples.foreach(example => example.accumulateExampleInto(gradientAccumulator, valueAccumulator))
    val ellapsedTime = System.currentTimeMillis - startTime
    logger.info(TrainerHelpers.getBatchTrainerStatus(gradientAccumulator.tensorSet.oneNorm, valueAccumulator.value, ellapsedTime))
    optimizer.step(weightsSet, gradientAccumulator.tensorSet, valueAccumulator.value)
  }
  def isConverged = optimizer.isConverged
}

// Learns the parameters of a model by computing the gradient and calling the
// optimizer one example at a time.
class OnlineTrainer(val weightsSet: WeightsSet, val optimizer: GradientOptimizer = new AdaGrad, val maxIterations: Int = 3, var logEveryN: Int = -1) extends Trainer with util.FastLogging {
  var gradientAccumulator = new LocalWeightsMapAccumulator(weightsSet.newBlankSparse)
  var iteration = 0
  val valueAccumulator = new LocalDoubleAccumulator
  override def processExamples(examples: Iterable[Example]): Unit = {
    if (logEveryN == -1) logEveryN = math.max(100, examples.size / 10)
    iteration += 1
    var valuesSeenSoFar = 0.0
    var timePerIteration = 0L
    examples.zipWithIndex.foreach({ case (example, i) => {
      if ((i % logEveryN == 0) && (i != 0)) {
        logger.info(TrainerHelpers.getOnlineTrainerStatus(i, logEveryN, timePerIteration, valuesSeenSoFar))
        valuesSeenSoFar = 0.0
        timePerIteration = 0
      }
      val t0 = System.currentTimeMillis()
      gradientAccumulator.tensorSet.zero()
      valueAccumulator.value = 0
      example.accumulateExampleInto(gradientAccumulator, valueAccumulator)
      valuesSeenSoFar += valueAccumulator.value
      optimizer.step(weightsSet, gradientAccumulator.tensorSet, valueAccumulator.value)
      timePerIteration += System.currentTimeMillis() - t0
    }})
  }
  def isConverged = iteration >= maxIterations
}

/** Train using one trainer, until it has converged, and then use the second trainer instead.
    Typically use is to first train with an online stochastic gradient ascent such as OnlineTrainer and AdaGrad,
    and then a batch trainer, like BatchTrainer and LBFGS. */
class TwoStageTrainer(firstTrainer: Trainer, secondTrainer: Trainer) {
  def processExamples(examples: Iterable[Example]) {
    if (!firstTrainer.isConverged)
      firstTrainer.processExamples(examples)
    else
      secondTrainer.processExamples(examples)
  }
  def isConverged = firstTrainer.isConverged && secondTrainer.isConverged
}

// This parallel batch trainer keeps a single gradient in memory and locks accesses to it.
// It is useful when computing the gradient in each example is more expensive than
// adding this gradient to the accumulator.
// If it performs slowly then minibatches should help, or the ThreadLocalBatchTrainer.
class ParallelBatchTrainer(val weightsSet: WeightsSet, val optimizer: GradientOptimizer = new LBFGS with L2Regularization, val nThreads: Int = Runtime.getRuntime.availableProcessors())
  extends Trainer with FastLogging {
  val gradientAccumulator = new SynchronizedWeightsMapAccumulator(weightsSet.newBlankDense)
  val valueAccumulator = new SynchronizedDoubleAccumulator
  def processExamples(examples: Iterable[Example]): Unit = {
    if (isConverged) return
    gradientAccumulator.l.tensorSet.zero()
    valueAccumulator.l.value = 0
    val startTime = System.currentTimeMillis
    TrainerHelpers.parForeach(examples.toSeq, nThreads)(_.accumulateExampleInto(gradientAccumulator, valueAccumulator))
    val ellapsedTime = System.currentTimeMillis - startTime
    logger.info(TrainerHelpers.getBatchTrainerStatus(gradientAccumulator.l.tensorSet.oneNorm, valueAccumulator.l.value, ellapsedTime))
    optimizer.step(weightsSet, gradientAccumulator.tensorSet, valueAccumulator.l.value)
  }
  def isConverged = optimizer.isConverged
}

// This parallel batch trainer keeps a per-thread gradient to which examples add weights.
// It is useful when there is a very large number of examples, processing each example is
// fast, and the weights are not too big, as it has to keep one copy of the weights per thread.
class ThreadLocalBatchTrainer(val weightsSet: WeightsSet, val optimizer: GradientOptimizer = new LBFGS with L2Regularization) extends Trainer with FastLogging {
  def processExamples(examples: Iterable[Example]): Unit = {
    if (isConverged) return
    val gradientAccumulator = new ThreadLocal(new LocalWeightsMapAccumulator(weightsSet.newBlankDense))
    val valueAccumulator = new ThreadLocal(new LocalDoubleAccumulator)
    val startTime = System.currentTimeMillis
    examples.par.foreach(example => example.accumulateExampleInto(gradientAccumulator.get, valueAccumulator.get))
    val grad = gradientAccumulator.instances.reduce((l, r) => { l.combine(r); l }).tensorSet
    val value = valueAccumulator.instances.reduce((l, r) => { l.combine(r); l }).value
    val ellapsedTime = System.currentTimeMillis - startTime
    logger.info(TrainerHelpers.getBatchTrainerStatus(grad.oneNorm, value, ellapsedTime))
    optimizer.step(weightsSet, grad, value)
  }
  def isConverged = optimizer.isConverged
}

// This uses read-write locks on the tensors to ensure consistency while doing
// parallel online training.
// The guarantee is that while the examples read each tensor they will see a consistent
// state, but this might not be the state the gradients will get applied to.
// The optimizer, however, has no consistency guarantees across tensors.
class ParallelOnlineTrainer(weightsSet: WeightsSet, val optimizer: GradientStep, val maxIterations: Int = 3, var logEveryN: Int = -1, val nThreads: Int = Runtime.getRuntime.availableProcessors())
 extends Trainer with FastLogging {
  var iteration = 0
  var initialized = false
  var examplesProcessed = 0
  var accumulatedValue = 0.0
  var t0 = 0L

  private def processExample(e: Example): Unit = {
    val gradient = weightsSet.newBlankSparse
    val gradientAccumulator = new LocalWeightsMapAccumulator(gradient)
    val value = new LocalDoubleAccumulator()
    e.accumulateExampleInto(gradientAccumulator, value)
    // The following line will effectively call makeReadable on all the sparse tensors before acquiring the lock
    gradient.tensors.foreach(t => if (t.isInstanceOf[SparseIndexedTensor]) t.asInstanceOf[SparseIndexedTensor].apply(0))
    optimizer.step(weightsSet, gradient, value.value)
    this synchronized {
      examplesProcessed += 1
      accumulatedValue += value.value
      if (examplesProcessed % logEveryN == 0) {
        val accumulatedTime = System.currentTimeMillis() - t0
        logger.info(TrainerHelpers.getOnlineTrainerStatus(examplesProcessed, logEveryN, accumulatedTime, accumulatedValue))
        t0 = System.currentTimeMillis()
        accumulatedValue = 0
      }
    }
  }

  def processExamples(examples: Iterable[Example]) {
    if (!initialized) replaceTensorsWithLocks()
    t0 = System.currentTimeMillis()
    examplesProcessed = 0
    accumulatedValue = 0.0
    if (logEveryN == -1) logEveryN = math.max(100, examples.size / 10)
    iteration += 1
    TrainerHelpers.parForeach(examples.toSeq, nThreads)(processExample(_))
  }

  def isConverged = iteration >= maxIterations

  def replaceTensorsWithLocks() {
    for (key <- weightsSet.keys) {
      key.value match {
        case t: Tensor1 => weightsSet(key) = new LockingTensor1(t)
        case t: Tensor2 => weightsSet(key) = new LockingTensor2(t)
        case t: Tensor3 => weightsSet(key) = new LockingTensor3(t)
        case t: Tensor4 => weightsSet(key) = new LockingTensor4(t)
      }
    }
    initialized = true
  }

  private trait LockingTensor extends Tensor {
    val base: Tensor
    val lock = new util.RWLock
    def activeDomain = base.activeDomain
    def isDense = base.isDense
    def zero() { lock.withWriteLock(base.zero())}
    def +=(i: Int, incr: Double) { lock.withWriteLock( base.+=(i,incr))}
    override def +=(i: DoubleSeq, v: Double) = lock.withWriteLock(base.+=(i,v))
    def dot(ds: DoubleSeq) = lock.withReadLock(base.dot(ds))
    def update(i: Int, v: Double) { lock.withWriteLock(base.update(i,v)) }
    def apply(i: Int) = lock.withReadLock(base.apply(i))
  }

  private class LockingTensor1(val base: Tensor1) extends Tensor1 with LockingTensor {
    def dim1 = base.dim1
  }
  private class LockingTensor2(val base: Tensor2) extends Tensor2 with LockingTensor {
    def dim1 = base.dim1
    def dim2 = base.dim2
    def activeDomain1 = lock.withReadLock(base.activeDomain1)
    def activeDomain2 = lock.withReadLock(base.activeDomain2)
    override def *(other: Tensor1) = lock.withReadLock(base * other)
  }
  private class LockingTensor3(val base: Tensor3) extends Tensor3 with LockingTensor {
    def dim1 = base.dim1
    def dim2 = base.dim2
    def dim3 = base.dim3
    def activeDomain1 = lock.withReadLock(base.activeDomain1)
    def activeDomain2 = lock.withReadLock(base.activeDomain2)
    def activeDomain3 = lock.withReadLock(base.activeDomain3)
  }
  private class LockingTensor4(val base: Tensor4) extends Tensor4 with LockingTensor {
    def dim1 = base.dim1
    def dim2 = base.dim2
    def dim3 = base.dim3
    def dim4 = base.dim4
    def activeDomain1 = lock.withReadLock(base.activeDomain1)
    def activeDomain2 = lock.withReadLock(base.activeDomain2)
    def activeDomain3 = lock.withReadLock(base.activeDomain3)
    def activeDomain4 = lock.withReadLock(base.activeDomain4)
  }
}

// This online trainer synchronizes only on the optimizer, so reads on the weights
// can be done while they are being written to.
// It provides orthogonal guarantees than the ParallelOnlineTrainer, as the examples can have
// inconsistent reads from the same tensor but the optimizer will always
// have a consistent view of all tensors.
class SynchronizedOptimizerOnlineTrainer(val weightsSet: WeightsSet, val optimizer: GradientOptimizer, val nThreads: Int = Runtime.getRuntime.availableProcessors(), val maxIterations: Int = 3, var logEveryN : Int = -1)
  extends Trainer with FastLogging {
  var examplesProcessed = 0
  var accumulatedValue = 0.0
  var t0 = System.currentTimeMillis()
  private def processExample(e: Example): Unit = {
    val gradient = weightsSet.newBlankSparse
    val gradientAccumulator = new LocalWeightsMapAccumulator(gradient)
    val value = new LocalDoubleAccumulator()
    e.accumulateExampleInto(gradientAccumulator, value)
    // The following line will effectively call makeReadable on all the sparse tensors before acquiring the lock
    gradient.tensors.foreach(t => if (t.isInstanceOf[SparseIndexedTensor]) t.asInstanceOf[SparseIndexedTensor].apply(0))
    optimizer synchronized {
      optimizer.step(weightsSet, gradient, value.value)
      examplesProcessed += 1
      accumulatedValue += value.value
      if (examplesProcessed % logEveryN == 0) {
        val accumulatedTime = System.currentTimeMillis() - t0
        logger.info(TrainerHelpers.getOnlineTrainerStatus(examplesProcessed, logEveryN, accumulatedTime, accumulatedValue))
        t0 = System.currentTimeMillis()
        accumulatedValue = 0
      }
    }
  }
  var iteration = 0
  def processExamples(examples: Iterable[Example]): Unit = {
    if (logEveryN == -1) logEveryN = math.max(100, examples.size / 10)
    iteration += 1
    t0 = System.currentTimeMillis()
    examplesProcessed = 0
    accumulatedValue = 0.0
    TrainerHelpers.parForeach(examples.toSeq, nThreads)(processExample(_))
  }
  def isConverged = iteration >= maxIterations
}

object TrainerHelpers {
  import scala.collection.JavaConversions._

  def getTimeString(ms: Long): String =
    if (ms > 120000) "%d minutes" format (ms / 60000) else if (ms> 5000) "%d seconds" format (ms / 1000) else "%d milliseconds" format ms
  def getBatchTrainerStatus(gradNorm: => Double, value: => Double, ms: => Long) =
    "GradientNorm: %-10g  value %-10g %s" format (gradNorm, value, getTimeString(ms))
  def getOnlineTrainerStatus(examplesProcessed: Int, logEveryN: Int, accumulatedTime: Long, accumulatedValue: Double) =
    examplesProcessed + " examples at " + (1000.0*logEveryN/accumulatedTime) + " examples/sec. Average objective: " + (accumulatedValue / logEveryN)

  def parForeach[In](xs: Iterable[In], numThreads: Int)(body: In => Unit): Unit = withThreadPool(numThreads)(p => parForeach(xs, p)(body))
  def parForeach[In](xs: Iterable[In], pool: ExecutorService)(body: In => Unit): Unit = {
    val futures = xs.map(x => javaAction(body(x)))
    pool.invokeAll(futures).toSeq
  }

  def javaAction(in: => Unit): Callable[Object] = new Callable[Object] { def call(): Object = {in; null} }
  def javaClosure[A](in: => A): Callable[A] = new Callable[A] { def call(): A = in }

  def newFixedThreadPool(numThreads: Int) = Executors.newFixedThreadPool(numThreads)
  def withThreadPool[A](numThreads: Int)(body: ExecutorService => A) = {
    val pool = newFixedThreadPool(numThreads)
    val res = body(pool)
    pool.shutdown()
    res
  }
}