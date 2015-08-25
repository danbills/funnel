package funnel

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scalaz.concurrent.Task

class TimerNCounter (
  timer: Timer[Periodic[Stats]],
  counter: Counter
) {

  /** Record the given duration, in nanoseconds. */
  def recordNanos(nanos: Long): Unit = {
    counter.increment
    timer.recordNanos(nanos)
  }

  /** Record the given duration. */
  def record(d: Duration): Unit = {
    counter.increment
    timer.record(d)
  }

  /**
   * Return a newly running stopwatch. To record
   * a time, call the returned stopwatch. Example:
   *
   *    val T: Timer = ...
   *    val stopwatch = T.start
   *    doSomeStuff()
   *    // ... and we're done
   *    stopwatch()
   *    // alternately, `T.stop(stopwatch)`
   *
   * Reusing a stopwatch is not recommended; it will
   * record the time since the stopwatch was first
   * created.
   */
  def start: () => Unit = {
    counter.increment
    timer.start
  }

  /** A bit of syntax for stopping a stopwatch returned from `start`. */
  def stop(stopwatch: () => Unit): Unit = {
    timer.stop(stopwatch)
  }

  /**
   * Evaluate `a` and record its evaluation time even if
   * evaluation completes with an error. Use `timeSuccess`
   * if you'd like to record a time only in the sucsessful case.
   */
  def time[A](a: => A): A = {
    counter.increment
    timer.time(a)
  }

  /**
   * Like `time`, but records a time only if evaluation of
   * `a` completes without error.
   */
  def timeSuccess[A](a: => A): A = {
    counter.increment
    timer.timeSuccess(a)
  }

  /**
   * Time a `Future` by registering a callback on its
   * `onComplete` method. The stopwatch begins now.
   * This function records a time regardless if the `Future`
   * completes with an error or not. Use `timeFutureSuccess` or
   * explicit calls to `start` and `stop` if you'd like to
   * record a time only in the event the `Future` succeeds.
   */
  def timeFuture[A](f: Future[A])(implicit ctx: ExecutionContext = ExecutionContext.Implicits.global): Future[A] = {
    counter.increment
    timer.timeFuture(f)(ctx)
  }

  /**
   * Like `timeFuture`, but records a time only if `f` completes
   * without an exception.
   */
  def timeFutureSuccess[A](f: Future[A])(implicit ctx: ExecutionContext = ExecutionContext.Implicits.global): Future[A] = {
    counter.increment
    timer.timeFutureSuccess(f)(ctx)
  }

  /**
   * Time an asynchronous `Task`. The stopwatch begins running when
   * the returned `Task` is run and a stop time is recorded if the
   * `Task` completes in any state. Use `timeTaskSuccess` if you
   * wish to only record times when the `Task` succeeds.
   */
  def timeTask[A](a: Task[A]): Task[A] = {
    timer.timeTask(a) map { res =>
      counter.increment
      res
    }
  }

  /**
   * Like `timeTask`, but records a time even if the `Task` completes
   * with an error.
   */
  def timeTaskSuccess[A](a: Task[A]): Task[A] = {
    timer.timeTaskSuccess(a) map { res =>
      counter.increment
      res
    }
  }

  /**
   * Time a currently running asynchronous task. The
   * stopwatch begins now, and finishes when the
   * callback is invoked with the result.
   */
  def timeAsync[A](register: (A => Unit) => Unit): Unit = {
    counter.increment
    timer.timeAsync(register)
  }

}
