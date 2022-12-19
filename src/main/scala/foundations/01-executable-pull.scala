/**
 * PULL-BASED STREAMS
 *
 * There are two opposite ways to create any streaming toolkit: using
 * a pushed-based engine, or using a pull-based engine. Each has its own
 * strenths and weaknesses.
 *
 * Akka Streams is an example of push-based, while FS2 is pull-based.
 *
 * Pull-based streams are lazy and do minimal computation, which makes them
 * equivalent to end-to-end backpressuring. They are adept at fan-in
 * operations, such as concurrent merge, but are not adept at fan-out.
 */
package foundations.streams.pull

import zio._
import zio.test._
import zio.test.TestAspect._
import scala.annotation.tailrec
import java.{ util => ju }

/**
 * Pull-based streams are "pulled from" by the consumer, which typically "runs"
 * the stream to obtain a result or do something with the elements of the
 * stream. Pull-based streams are conceptually similar to an iterator, except
 * you do not the ability to specifically request a "next" element; but rather,
 * certain operations may implicitly pull more elements from the stream.
 */
object PullBased extends ZIOSpecDefault {
  trait ClosableIterator[+A] extends AutoCloseable { self =>
    def hasNext: Boolean
    def next(): A

    def ++[A1 >: A](that0: => ClosableIterator[A1]): ClosableIterator[A1] = new ClosableIterator[A1] {
      private var current: ClosableIterator[A1] = self
      private var isFirst: Boolean              = true
      private lazy val that                     = that0

      def hasNext =
        if (current.hasNext) true
        else {
          if (isFirst) {
            isFirst = false
            current.close()
            current = that
            that.hasNext
          } else false
        }

      def next() =
        if (hasNext) current.next()
        else throw new NoSuchElementException("next() called on empty iterator")

      def close() = current.close()
    }

    def map[B](f: A => B): ClosableIterator[B] = new ClosableIterator[B] {
      def hasNext = self.hasNext
      def next()  = f(self.next())
      def close() = self.close()
    }

    def flatMap[B](f: A => ClosableIterator[B]): ClosableIterator[B] = new ClosableIterator[B] {
      private var current: ClosableIterator[B] = null

      def hasNext = {
        @tailrec
        def loop: Boolean =
          if (current == null) {
            if (self.hasNext) {
              current = f(self.next())
              loop
            } else false
          } else if (current.hasNext) true
          else {
            current.close()
            current = null
            loop
          }

        loop
      }

      def next() =
        if (!hasNext) throw new NoSuchElementException("next() called on empty iterator")
        else current.next()

      def close() = {
        if (current != null) current.close()
        self.close()
      }
    }
  }

  trait Stream[+A] { self =>
    def iterator(): ClosableIterator[A]

    final def map[B](f: A => B): Stream[B] =
      new Stream[B] {
        def iterator(): ClosableIterator[B] = self.iterator().map(f)
      }

    final def take(n: Int): Stream[A] =
      new Stream[A] {
        def iterator(): ClosableIterator[A] =
          new ClosableIterator[A] {
            val outer = self.iterator()
            var taken = 0

            def hasNext: Boolean =
              (taken < n) && outer.hasNext

            def next(): A = {
              val element = outer.next()

              taken = taken + 1

              element
            }

            def close(): Unit = outer.close()
          }
      }

    final def drop(n: Int): Stream[A] =
      new Stream[A] {
        def iterator(): ClosableIterator[A] =
          new ClosableIterator[A] {
            val outer = self.iterator()

            (1 to n).foreach { _ =>
              if (outer.hasNext) outer.next()
            }

            def hasNext: Boolean = outer.hasNext

            def next(): A = outer.next()

            def close(): Unit = outer.close()
          }
      }

    final def filter(f: A => Boolean): Stream[A] =
      new Stream[A] {
        def iterator(): ClosableIterator[A] =
          new ClosableIterator[A] {
            val outer  = self.iterator()
            var nextEl = findNext()

            @tailrec
            def findNext(): Option[A] =
              if (!outer.hasNext) None
              else {
                val e = outer.next()
                if (f(e)) Some(e)
                else findNext()
              }

            def hasNext: Boolean = nextEl.isDefined

            def next(): A = {
              val e = nextEl.get
              nextEl = findNext()
              e
            }

            def close(): Unit = outer.close()
          }
      }

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] =
      new Stream[A1] {
        def iterator(): ClosableIterator[A1] =
          new ClosableIterator[A1] {
            var current: ClosableIterator[A1] = self.iterator()
            var inLeft                        = true

            @tailrec
            def hasNext: Boolean = {
              val isNext = current.hasNext

              if (!isNext && inLeft) {
                current.close()
                current = that.iterator()
                inLeft = false
                hasNext
              } else isNext
            }

            def next(): A1 = current.next()

            def close(): Unit = current.close()
          }
      }

    final def flatMap[B](f: A => Stream[B]): Stream[B] =
      new Stream[B] {
        def iterator(): ClosableIterator[B] =
          self.iterator().flatMap(a => f(a).iterator())
      }

    final def mapAccum[S, B](initial: S)(f: (S, A) => (S, B)): Stream[B] =
      new Stream[B] {
        def iterator(): ClosableIterator[B] =
          new ClosableIterator[B] {
            val iter  = self.iterator()
            var state = initial

            def close(): Unit = iter.close()

            def hasNext: Boolean = iter.hasNext

            def next(): B = {
              val a = iter.next()

              val (newState, b) = f(state, a)

              state = newState

              b
            }
          }
      }

    final def foldLeft[S](initial: S)(f: (S, A) => S): S = {
      var state = initial
      val iter  = iterator()

      try {
        while (iter.hasNext) {
          state = f(state, iter.next())
        }
      } finally {
        iter.close()
      }

      state
    }

    final def zip[B](that: Stream[B]): Stream[(A, B)] =
      new Stream[(A, B)] {
        def iterator(): ClosableIterator[(A, B)] =
          new ClosableIterator[(A, B)] {
            val left  = self.iterator()
            val right = that.iterator()

            def close(): Unit =
              try left.close()
              finally right.close()

            def hasNext: Boolean = left.hasNext && right.hasNext

            def next(): (A, B) = (left.next(), right.next())
          }
      }

    final def merge[A1 >: A](that: Stream[A1]): Stream[A1] =
      new Stream[A1] {
        def iterator(): ClosableIterator[A1] =
          new ClosableIterator[A1] {
            val left     = self.iterator()
            val right    = that.iterator()
            var readLeft = true

            def hasNext: Boolean = left.hasNext || right.hasNext

            def close(): Unit =
              try left.close()
              finally right.close()

            @tailrec
            def next(): A1 =
              if (!left.hasNext && !right.hasNext) throw new NoSuchElementException("Illegal invocation of next()")
              else if (readLeft) {
                if (left.hasNext) {
                  readLeft = false
                  left.next()
                } else {
                  readLeft = false
                  next()
                }
              } else {
                if (right.hasNext) {
                  readLeft = true
                  right.next()
                } else {
                  readLeft = true
                  next()
                }
              }

          }
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
        def iterator() = new ClosableIterator[A] {
          private val as    = as0.toVector
          private var index = 0

          def hasNext = index < as.length
          def next() = {
            val a = as(index)
            index += 1
            a
          }
          def close() = ()
        }
      }

    def unfold[S, A](initial: S)(f: S => Option[S]): Stream[S] =
      new Stream[S] {
        def iterator(): ClosableIterator[S] =
          new ClosableIterator[S] {
            var next0: Option[S] = Some(initial)

            def close(): Unit = ()

            def hasNext: Boolean = next0.isDefined

            def next(): S = {
              var s = next0.get

              next0 = f(s)

              s
            }
          }
      }

    def iterate[S](initial: S)(f: S => S): Stream[S] = unfold(initial)(s => Some(f(s)))

    def attempt[A](a: => A): Stream[A] =
      new Stream[A] {
        def iterator(): ClosableIterator[A] =
          new ClosableIterator[A] {
            lazy val element = a
            var isFirst      = true

            def hasNext: Boolean = isFirst

            def close(): Unit = ()

            def next(): A =
              if (!isFirst) {
                throw new NoSuchElementException("Already consumed singleton element")
              } else {
                isFirst = false
                element
              }
          }
      }

    def suspend[A](stream: => Stream[A]): Stream[A] =
      new Stream[A] {
        def iterator(): ClosableIterator[A] =
          new ClosableIterator[A] {
            lazy val streamIter = stream.iterator()

            def close(): Unit = streamIter.close()

            def hasNext: Boolean = streamIter.hasNext

            def next(): A = streamIter.next()
          }
      }

    def fromFile(file: String): Stream[Byte] = {
      import java.io.FileInputStream

      new Stream[Byte] {
        def iterator(): ClosableIterator[Byte] =
          new ClosableIterator[Byte] {
            val fis  = new FileInputStream(file)
            var byte = fis.read()

            def close(): Unit = fis.close()

            def hasNext: Boolean = byte >= 0

            def next(): Byte = {
              if (byte < 0) throw new ju.NoSuchElementException("There are no more bytes to read")

              val b = byte.toByte

              byte = fis.read()

              b
            }
          }
      }
    }
  }

  def spec = suite("PullBasedSpec") {
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
            lazy val evenIntegers: Stream[Int] = ???

            assertTrue(evenIntegers.take(2).runCollect == Chunk(2, 4))
          } @@ ignore
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
      suite("pull strengths") {

        /**
         * EXERCISE
         *
         * Implement `Stream#zip` in such a way as to make this test case pass.
         */
        test("zip") {
          val stream1 = Stream(1, 2, 3, 4)
          val stream2 = Stream(5, 6, 7, 8)

          for {
            zipped <- ZIO.succeed(stream1.zip(stream2))
          } yield assertTrue(zipped.runCollect == Chunk((1, 5), (2, 6), (3, 7), (4, 8)))
        } +
          /**
           * EXERCISE
           *
           * Implement `Stream#merge` in such a way as to make this test case pass.
           */
          test("merge") {
            val stream1 = Stream(1, 2, 3, 4)
            val stream2 = Stream(5, 6, 7, 8)

            for {
              merged <- ZIO.succeed(stream1.merge(stream2))
            } yield assertTrue(merged.runCollect == Chunk(1, 5, 2, 6, 3, 7, 4, 8))
          }
      }
  }
}
