/**
 * PUSH-BASED STREAMS
 *
 * Push-based streams, such as Akka streams, excel at concurrent workloads,
 * though they require extensive backpressure. They are adept at fan-out
 * operations, such as concurrent broadcast, but are not adept at fan-in.
 *
 * In this section, you will explore the architecture of push-based streams
 * and learn from experience why some operations are easier than others.
 */
package foundations.push

import zio._
import zio.test._
import zio.test.TestAspect._
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.io.FileInputStream

/**
 * Push-based streams are "pushed to" by the producer, and ultimately
 * pushed onto the consumer. Push-based streams have an asynchronous
 * core, which is built on callbacks. Typically, push-based streams
 * need some mechanism to backpressure the producer, so that it does
 * not overwhelm the consumer.
 *
 * In this spec, you will explore a simple push-based stream encoding
 * that uses callbacks for new elements and callbacks for stream
 * termination. Notably, this model does not include backpressure.
 */
object PushBased extends ZIOSpecDefault {
  trait Stream[+A] { self =>
    def receive(onElement: A => Unit, onDone: () => Unit): Unit

    final def ensuring(finalizer: => Unit): Stream[A] =
      new Stream[A] {
        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          self.receive(onElement, () => {
            try finalizer
            finally onDone()
          })
      }

    final def map[B](f: A => B): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit =
          self.receive(a => onElement(f(a)), onDone)
      }

    final def take(n: Int): Stream[A] =
      new Stream[A] {
        val taken = new AtomicInteger(0)

        def receive(onElement: A => Unit, onDone0: () => Unit): Unit = {
          val onDone = Stream.once(onDone0)

          self.receive({ a =>
            val t = taken.getAndIncrement()
            if (t < n) onElement(a)
            else {
              onDone()
            }
          }, onDone)
        }
      }

    final def drop(n: Int): Stream[A] =
      new Stream[A] {
        val dropped = new AtomicInteger(0)

        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          self.receive({ a =>
            val t = dropped.getAndIncrement()
            if (t >= n) onElement(a)
          }, onDone)
      }

    final def filter(f: A => Boolean): Stream[A] =
      self.flatMap(a => if (f(a)) Stream(a) else Stream.empty)

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

    final def mapAccum[S, B](initial: S)(f: (S, A) => (S, B)): Stream[B] =
      new Stream[B] {
        def receive(onElement: B => Unit, onDone: () => Unit): Unit = {
          val stateRef = new AtomicReference[S](initial)

          self.receive(a => {
            var b: Option[B] = None

            stateRef.updateAndGet(s => {
              val (s1, b1) = f(s, a)
              b = Some(b1)
              s1
            })

            onElement(b.get)
          }, onDone)
        }
      }

    final def foldLeft[S](initial: S)(f: (S, A) => S): S = {
      val stateRef = new AtomicReference[S](initial)

      val countDownLatch = new java.util.concurrent.CountDownLatch(1)

      receive(a => stateRef.updateAndGet(s0 => f(s0, a)), () => countDownLatch.countDown())

      countDownLatch.await()

      stateRef.get()
    }

    final def duplicate: (Stream[A], Stream[A]) = {
      val subscribers = new AtomicReference[List[(A => Unit, () => Unit)]](Nil)
      val subscribed  = new CountDownLatch(2)

      scala.concurrent.ExecutionContext.global.execute { () =>
        subscribed.await()

        self.receive(a => subscribers.get.map(_._1).foreach(_(a)), () => subscribers.get.map(_._2).foreach(_()))
      }

      val s: Stream[A] =
        new Stream[A] {
          def receive(onElement: A => Unit, onDone: () => Unit): Unit = {
            subscribers.updateAndGet(subscribers => (onElement, onDone) :: subscribers)
            subscribed.countDown()
          }
        }

      (s, s)
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
    private def once(f: () => Unit): () => Unit = {
      val called = new AtomicBoolean(false)

      () => {
        if (called.compareAndSet(false, true)) f()
        else ()
      }
    }

    def apply[A](as0: A*): Stream[A] =
      new Stream[A] {
        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          try as0.foreach(onElement)
          finally onDone()
      }

    val empty: Stream[Nothing] = Stream[Nothing]()

    def unfold[S, A](initial: S)(f: S => Option[S]): Stream[S] =
      Stream(initial) ++ f(initial).fold[Stream[S]](Stream.empty)(s => unfold(s)(f))

    def iterate[S](initial: S)(f: S => S): Stream[S] = unfold(initial)(s => Some(f(s)))

    def attempt[A](a: => A): Stream[A] =
      suspend(Stream(a))

    def suspend[A](stream0: => Stream[A]): Stream[A] =
      new Stream[A] {
        lazy val stream = stream0

        def receive(onElement: A => Unit, onDone: () => Unit): Unit =
          stream.receive(onElement, onDone)
      }

    def fromFile(file: String): Stream[Byte] =
      Stream.suspend {
        val fis = new FileInputStream(file)

        def readBytes(): Stream[Byte] =
          Stream.suspend {
            val int = fis.read()

            if (int < 0) Stream()
            else Stream(int.toByte) ++ readBytes()
          }

        readBytes().ensuring(fis.close())
      }
  }

  def spec =
    suite("PushBasedSpec") {
      suite("core operators") {

        /**
         * EXERCISE
         *
         * Implement the `Stream#map` method in such a way as to make this test
         * case pass.
         */
        test("map") {
          val stream = Stream(1, 2, 3, 4)

          for {
            mapped <- ZIO.succeed(stream.map(_ + 1))
          } yield assertTrue(mapped.runCollect == Chunk(2, 3, 4, 5))
        } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#take` method in such a way as to make this test
           * case pass.
           */
          test("take") {
            val stream = Stream(1, 2, 3, 4)

            for {
              taken <- ZIO.succeed(stream.take(2))
            } yield assertTrue(taken.runCollect == Chunk(1, 2))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#drop` method in such a way as to make this test
           * case pass.
           */
          test("drop") {
            val stream = Stream(1, 2, 3, 4)

            for {
              dropped <- ZIO.succeed(stream.drop(2))
            } yield assertTrue(dropped.runCollect == Chunk(3, 4))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#filter` method in such a way as to make this test
           * case pass.
           */
          test("filter") {
            val stream = Stream(1, 2, 3, 4)

            for {
              filtered <- ZIO.succeed(stream.filter(_ % 2 == 0))
            } yield assertTrue(filtered.runCollect == Chunk(2, 4))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#++` method in such a way as to make this test
           * case pass.
           */
          test("++") {
            val stream1 = Stream(1, 2, 3, 4)
            val stream2 = Stream(5, 6, 7, 8)

            for {
              appended <- ZIO.succeed(stream1 ++ stream2)
            } yield assertTrue(appended.runCollect == Chunk(1, 2, 3, 4, 5, 6, 7, 8))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#flatMap` method in such a way as to make this test
           * case pass.
           */
          test("flatMap") {
            val stream = Stream(1, 2, 3, 4)

            for {
              flatMapped <- ZIO.succeed(stream.flatMap(a => Stream(a, a)))
            } yield assertTrue(flatMapped.runCollect == Chunk(1, 1, 2, 2, 3, 3, 4, 4))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#mapAccum` method in such a way as to make this test
           * case pass.
           */
          test("mapAccum") {
            val stream = Stream(1, 2, 3, 4)

            for {
              mapped <- ZIO.succeed(stream.mapAccum(0)((s, a) => (s + a, s + a)))
            } yield assertTrue(mapped.runCollect == Chunk(1, 3, 6, 10))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Stream#foldLeft` method in such a way as to make this test
           * case pass.
           */
          test("foldLeft") {
            val stream = Stream(1, 2, 3, 4)

            for {
              folded <- ZIO.succeed(stream.foldLeft(0)(_ + _))
            } yield assertTrue(folded == 10)
          }
      } +
        suite("advanced constructors") {

          /**
           * EXERCISE
           *
           * Implement the `Stream.unfold` constructor in such a way as to make
           * this test case pass.
           */
          test("unfold") {
            val integers =
              Stream.unfold(0)(i => Some((i + 1)))

            assertTrue(integers.take(5).runCollect.length == 5)
          } @@ ignore +
            /**
             * EXERCISE
             *
             * Use `Stream.iterate` and methods `Stream` to produce a list of all
             * positive integers which are evenly divisible by 2. This should be
             * an infinite list!
             */
            test("iterate") {
              lazy val evenIntegers: Stream[Int] =
                Stream.iterate(1)(_ + 1).filter(_ % 2 == 0)

              assertTrue(evenIntegers.take(2).runCollect == Chunk(2, 4))
            }
        } +
        suite("resources") {

          /**
           * EXERCISE
           *
           * Implement `Stream.attempt` in such a way as to make this test case pass.
           *
           * Hint: Simply defer the creation of the stream until it is run.
           */
          test("attempt") {
            import java.io.FileInputStream

            Stream.attempt(new FileInputStream("build.sbt"))

            assertCompletes
          } @@ ignore +
            /**
             * EXERCISE
             *
             * Implement `Stream.suspend` in such a way as to make this test case pass.
             */
            test("suspend") {
              val blowup = Stream.suspend(???)

              assertCompletes
            } @@ ignore +
            /**
             * EXCERCISE
             *
             * Implement `Stream.fromFile` in such a way as to make this test case pass
             * without leaking any resources.
             */
            test("fromFile") {
              val stream = Stream.fromFile("build.sbt")

              assertTrue(stream.runCollect.length > 0)
            } @@ ignore
        } +
        suite("push strengths") {

          /**
           * EXERCISE
           *
           * Implement `Stream#duplicate` in such a way as to make this test case pass.
           */
          test("duplicate") {
            val stream = Stream(1, 2, 3, 4)

            val (left, right) = stream.duplicate

            for {
              fiber1 <- ZIO.succeed(left.runCollect).fork
              fiber2 <- ZIO.succeed(right.runCollect).fork
              left   <- fiber1.join
              right  <- fiber2.join
            } yield assertTrue(left == right)
          } @@ ignore
        }
    }
}
