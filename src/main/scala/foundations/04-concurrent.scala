/**
 * CONCURRENT
 *
 * If streams were only ever linear, without the ability to combine different
 * streams, aggregate elements, timeout and schedule elements, and so forth,
 * then they would be much less useful.
 *
 * Concurrency enables us to combine and modify streams in powerful ways that
 * satisfy complex use cases, improve performance, and introduce both
 * scheduling, interruption, async aggregation, and other capabilities.
 *
 * In this module, you will explore the foundations of concurrency in streaming
 * libraries. While the details may differ across different libraries, the
 * concepts you will learn in this section are broadly applicable and will
 * give you insight into all.
 */
package foundations.concurrent

import zio._

import zio.test._
import zio.test.TestAspect._
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledFuture

/**
 * In this section, you will use the executionable push-based stream encoding
 * to implement a variety of concurrent operators. These operators are not
 * easy to implement or get right. Nor is it necessarily obvious where to stop,
 * since the number of potentially useful concurrent operators is very high.
 */
object ConcurrentSpec extends ZIOSpecDefault {
  trait Stream[+A] { self =>
    def receive(onElement: A => Unit, onDone: () => Unit): Unit

    final def map[B](f: A => B): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit =
          self.receive(a => onElement(f(a)), onDone)
      }

    final def filter(f: A => Boolean): Stream[A] =
      new Stream[A] {
        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          self.receive(a => if (f(a)) onElement(a), onDone)
      }

    final def merge[A1 >: A](that: => Stream[A1]): Stream[A1] =
      new Stream[A1] {
        def receive(onElement: A1 => Unit, onDone0: () => Unit): Unit = {
          import scala.concurrent.ExecutionContext.global
          val doneCount = new AtomicInteger(0)
          val onDone = () => {
            if (doneCount.incrementAndGet() == 2) onDone0()
          }

          global.execute(() => self.receive(onElement, onDone))
          global.execute(() => that.receive(onElement, onDone))
        }
      }

    final def mapPar[B](f: A => B): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit = {
          import scala.concurrent.ExecutionContext.global
          val pendingAs = new AtomicInteger(0)

          self.receive(
            a => {
              pendingAs.incrementAndGet()
              global.execute { () =>
                onElement(f(a))
                pendingAs.decrementAndGet()
              }
            },
            () => {
              while (pendingAs.get() != 0) Thread.`yield`(); onDone()
            }
          )
        }
      }

    final def flatMapPar[B](f: A => Stream[B]): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit = {
          import scala.concurrent.ExecutionContext.global

          self.receive(a => global.execute(() => f(a).receive(onElement, () => ())), onDone)
        }
      }

    final def aggregateUntil(maxSize: Int, maxDelay: Duration): Stream[Chunk[A]] =
      new Stream[Chunk[A]] {
        val scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1)
        var scheduled = Option.empty[ScheduledFuture[_]]

        def receive(onElement: Chunk[A] => Unit, onDone: () => Unit): Unit = {
          val chunkRef = new AtomicReference[Chunk[A]](Chunk.empty)

          val sendAll: Runnable =
            () => {
              val toSend = chunkRef.getAndUpdate(_ => Chunk.empty)

              if (toSend.nonEmpty) onElement(toSend)
            }

          self.receive(
            { a =>
              scheduled.foreach(_.cancel(true))
              scheduled = Some(
                scheduler.schedule(sendAll, maxDelay.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
              )

              var toSend = Chunk.empty[A]

              var loop = true
              while (loop) {
                toSend = Chunk.empty

                val oldChunk = chunkRef.get
                val newChunk = chunkRef.get :+ a

                if (newChunk.length >= maxSize) {
                  toSend = newChunk
                  loop = !chunkRef.compareAndSet(oldChunk, Chunk.empty)
                } else {
                  loop = !chunkRef.compareAndSet(oldChunk, newChunk)
                }
              }

              if (toSend.nonEmpty) onElement(toSend)
            },
            () => {
              val chunk = chunkRef.getAndUpdate(_ => Chunk.empty)

              if (chunk.nonEmpty) onElement(chunk)

              onDone()
            }
          )
        }
      }

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] =
      new Stream[A1] {
        def receive(onElement: A1 => Unit, onDone: () => Unit): Unit =
          self.receive(onElement, () => that.receive(onElement, onDone))
      }

    final def flatMap[B](f: A => Stream[B]): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit =
          self.receive(a => f(a).receive(onElement, () => ()), onDone)
      }

    final def foldLeft[S](initial: S)(f: (S, A) => S): S = {
      val stateRef = new AtomicReference[S](initial)

      val countDownLatch = new java.util.concurrent.CountDownLatch(1)

      receive(a => stateRef.updateAndGet(s0 => f(s0, a)), () => countDownLatch.countDown())

      countDownLatch.await()

      stateRef.get()
    }

    final def mkString(sep: String): String =
      self.foldLeft("") {
        case (acc, a) =>
          if (acc.nonEmpty) acc + sep + a.toString()
          else a.toString()
      }

    final def runCollect: Chunk[A] = foldLeft[Chunk[A]](Chunk.empty[A])(_ :+ _)

    final def runLast: Option[A] = foldLeft[Option[A]](None) {
      case (_, a) => Some(a)
    }
  }
  object Stream {
    def apply[A](as0: A*): Stream[A] =
      new Stream[A] {
        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          try as0.foreach(onElement)
          finally onDone()
      }
  }

  def spec =
    suite("ConcurrentSpec")(
      /**
       * EXERCISE
       *
       * Implement the `merge` operator on streams, which combines two streams
       * into one stream concurrently.
       */
      test("merge") {
        val stream1 = Stream(1, 2, 3)
        val stream2 = Stream(4, 5, 6)

        val merged = stream1.merge(stream2)

        assertTrue(merged.runCollect.toSet == Set(1, 2, 3, 4, 5, 6))
      } @@ ignore,
      /**
       * EXERCISE
       *
       * Implement the `mapPar` operator on streams, which maps elements of a
       * stream concurrently.
       */
      test("mapPar") {
        val stream = Stream(1, 2, 3)

        val mapped = stream.mapPar(_ * 2)

        assertTrue(mapped.runCollect.toSet == Set(2, 4, 6))
      } @@ ignore,
      /**
       * EXERCISE
       *
       * Implement the `flatMapPar` operator on streams, which flatMaps elements
       * of a stream concurrently.
       */
      test("flatMapPar") {
        val stream = Stream(1, 2, 3)

        val mapped = stream.flatMapPar(a => Stream(a, a * 2))

        assertTrue(mapped.runCollect.toSet == Set(1, 2, 4, 3, 6))
      } @@ ignore,
      /**
       * EXERCISE
       *
       * Implement the `aggregateUntil` operator on streams, which batches elements
       * of a stream until a maximum size or maximum delay is reached.
       */
      test("aggregateUntil") {
        val stream = Stream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val batched = stream.aggregateUntil(3, 1.second)

        assertTrue(batched.runCollect.toSet == Set(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8, 9), Chunk(10)))
      } @@ ignore
    )
}
