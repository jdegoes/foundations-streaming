/**
 * PIPELINES
 *
 * Streams represent sources of values. But in building whole data pipelines,
 * sometimes it is useful to describe the ways that elements are transformed,
 * aggregated, or stored somewhere independently of the source of values itself.
 *
 * Different libraries have different solutions to describing the "whole pipeline"
 * in a reusable way. In this section, you will learn about some of the options.
 */
package foundations.pipeline

import zio._

import zio.test._
import zio.test.TestAspect._

import foundations.streams.intro.SimpleStream._
import java.util.concurrent.atomic.AtomicReference

/**
 * In this section, you will learn about pipelines as a _stream transformer_,
 * and sinks as a _stream consumer_. These simple data types are easy to
 * reason about, although they are not as powerful as fully-featured
 * streaming libraries. But they are a good starting point to gain some
 * intuition for what a "sink" and "pipeline" represent and some of their
 * capabilities and use cases.
 */
object SimplePipelineSpec extends ZIOSpecDefault {
  final case class Pipeline[-A, +B](run: Stream[A] => Stream[B]) { self =>
    def >>>[C](that: Pipeline[B, C]): Pipeline[A, C] = 
      Pipeline[A, C](self.run.andThen(that.run))
  }
  object Pipeline {
    def stringToChar: Pipeline[String, Char] =
      Pipeline(s => s.flatMap(str => Stream(str.toList: _*)))

    def chunked[A](n: Int): Pipeline[A, Chunk[A]] =
      Pipeline(
        s =>
          s.mapAccum(Chunk.empty[A]) {
              case (chunk, a) if chunk.length >= (n - 1) => (Chunk.empty, chunk)
              case (chunk, a)                            => (chunk :+ a, Chunk.empty)
            }
            .filter(_ != Chunk.empty)
      )

    def identity[A]: Pipeline[A, A] = Pipeline(s => s)

    def map[A, B](f: A => B): Pipeline[A, B] = Pipeline(_.map(f))

    def filter[A](f: A => Boolean): Pipeline[A, A] = Pipeline(_.filter(f))
  }
  final case class Sink[-A](run: Stream[A] => Unit) { self =>
    def &&[A1 <: A](that: Sink[A1]): Sink[A1] =
      Sink { stream =>
        self.run(stream)
        that.run(stream)
      }
  }
  object Sink {
    def drain: Sink[Any] = 
      Sink(_.foldLeft(())((_, _) => ()))

    def collectTo[A](ref: AtomicReference[Chunk[A]]): Sink[A] = 
      foreach(a => ref.updateAndGet(_ :+ a))

    def foreach[A](f: A => Unit): Sink[A] = 
      Sink(_.foldLeft[Unit](()) { case (_, a) => f(a) })

    def logElements[A](prefix: String): Sink[A] = 
      if (prefix == "") foreach(a => println(a.toString()))
      else foreach(a => println(s"$prefix: $a"))
  }

  def spec =
    suite("SimplePipelineSpec") {
      suite("Pipeline") {

        /**
         * EXERCISE
         *
         * Implement the `>>>` operator for `Pipeline`, which composes two
         * pipelines together. Implement it in a way that ensures the following
         * unit test succeeds.
         */
        test(">>>") {
          val pipeline = Pipeline[Int, Int](s => s) >>> Pipeline[Int, Int](_.map(_ + 1))

          val stream = Stream(1, 2, 3, 4, 5)

          val result = pipeline.run(stream).runCollect

          assertTrue(result == Chunk(2, 3, 4, 5, 6))
        } +
          /**
           * EXERCISE
           *
           * Implement the `Pipeline.identity` constructor in such a way as to
           * make the following unit test pass.
           */
          test("identity") {
            val pipeline = Pipeline.identity[Int]

            val stream = Stream(1, 2, 3, 4, 5)

            val result = pipeline.run(stream).runCollect

            assertTrue(result == Chunk(1, 2, 3, 4, 5))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Pipeline.map` constructor in such a way as to make
           * the following unit test pass.
           */
          test("map") {
            val pipeline = Pipeline.map[Int, Int](_ + 1)

            val stream = Stream(1, 2, 3, 4, 5)

            val result = pipeline.run(stream).runCollect

            assertTrue(result == Chunk(2, 3, 4, 5, 6))
          } +
          /**
           * EXERCISE
           *
           * Implement the `Pipeline.filter` constructor in such a way as to
           * make the following unit test pass.
           */
          test("filter") {
            val pipeline = Pipeline.filter[Int](_ % 2 == 0)

            val stream = Stream(1, 2, 3, 4, 5)

            val result = pipeline.run(stream).runCollect

            assertTrue(result == Chunk(2, 4))
          }
      } +
        suite("Sink") {

          /**
           * EXERCISE
           *
           * Implement the `&&` operator for `Sink`, which composes two sinks
           * together. Implement it in a way that ensures the following unit test
           * succeeds.
           */
          test("&&") {
            var i    = 0
            val sink = Sink.foreach[Int](i += _) && Sink.foreach[Int](i += _)

            val stream = Stream(1, 2, 3, 4, 5)

            val result = sink.run(stream)

            assertTrue(i == 30)
          } +
            /**
             * EXERCISE
             *
             * Implement the `Sink.foreach` constructor in such a way as to make
             * the following unit test pass.
             */
            test("foreach") {
              var i    = 0
              val sink = Sink.foreach[Int](i += _)

              val stream = Stream(1, 2, 3, 4, 5)

              val result = sink.run(stream)

              assertTrue(i == 15)
            } +
            /**
             * EXERCISE
             *
             * Implement the `Sink.logElements` constructor in such a way as to
             * log the elements to the console.
             */
            test("logElements") {
              val sink = Sink.logElements[Int]("logElements")

              val stream = Stream(1, 2, 3, 4, 5)

              sink.run(stream)

              assertCompletes
            }
        }
    }
}

/**
 * In this section, you will learn about slightly more powerful pipelines,
 * which have the ability to do many-to-many transformations; and
 * significantly more powerful sinks, which can produce typed values
 * from consuming a stream.
 */
object AdvancedPipelineSpec extends ZIOSpecDefault {
  final case class Pipeline[-A, +B](run: Stream[A] => Stream[B]) {
    def >>>[C](that: Pipeline[B, C]): Pipeline[A, C] =
      Pipeline(a => that.run(run(a)))

    def >>>[C](that: Sink[B, C]): Sink[A, C] = ???
  }
  object Pipeline {
    def identity[A]: Pipeline[A, A] =
      Pipeline(a => a)

    def map[A, B](f: A => B): Pipeline[A, B] =
      Pipeline(_.map(f))

    def filter[A](f: A => Boolean): Pipeline[A, A] =
      Pipeline(_.filter(f))

    def splitWords: Pipeline[String, String] = 
      Pipeline(stream => stream.flatMap(line => Stream(line.split("\\s+"): _*)))

    def transform[S, A, B](s: S)(f: (S, A) => (S, Chunk[B])): Pipeline[A, B] = 
      Pipeline[A, B] { stream =>
        stream.mapAccum(s) {
          case (s, a) => f(s, a)
        }.flatMap(chunk => Stream(chunk: _*))
      }
  }
  final case class Sink[-A, +B](run: Stream[A] => B) {
    def &&[A1 <: A, C](that: Sink[A1, C]): Sink[A1, (B, C)] = 
      Sink(a => (run(a), that.run(a)))

    def map[C](f: B => C): Sink[A, C] = 
      Sink(a => f(run(a)))
  }
  object Sink {

    def foreach[A](f: A => Unit): Sink[A, Unit] =
      Sink(_.foldLeft[Unit](())((_, a) => f(a)))

    def sum[A](implicit n: Numeric[A]): Sink[A, A] =
      Sink(_.foldLeft(n.zero)(n.plus))

    def max[A](implicit n: Numeric[A]): Sink[A, A] =
      Sink(_.foldLeft(n.zero)(n.max))

    def wordCount: Sink[String, Map[String, Int]] = 
      Sink(_.foldLeft(Map.empty[String, Int]) { 
        case (m, string) => 
         string.split("\\s+").foldLeft(m) {
          case (m, s) => m + (s -> (m.getOrElse(s, 0) + 1))
         }
      })

    def fold[S, A](s: S)(f: (S, A) => S): Sink[A, S] =
      Sink(_.foldLeft(s)(f))
  }

  def spec = suite("AdvancedPipelineSpec") {
    suite("Pipeline") {

      /**
       * EXERCISE
       *
       * Implement the `>>>` operator for `Pipeline`, which composes a pipeline
       * with a sink to yield a sink that works on a different type of input.
       */
      test(">>>") {
        val pipeline = Pipeline.map[Int, Int](_ + 1)

        val stream = Stream(1, 2, 3, 4, 5)

        val result = pipeline.run(stream).runCollect

        assertTrue(result == Chunk(2, 3, 4, 5, 6))
      } @@ ignore +
        /**
         * EXERCISE
         *
         * Implement the `Pipeline.splitWordds` constructor in such a way as to
         * make the following unit test pass.
         */
        test("splitWords") {
          val pipeline = Pipeline.splitWords

          val stream = Stream("Hello World", "Goodbye World")

          val result = pipeline.run(stream).runCollect

          assertTrue(result == Chunk("Hello", "World", "Goodbye", "World"))
        } @@ ignore

    } +
      suite("Sink") {
        suite("operators") {

          /**
           * EXERCISE
           *
           * Implement the `&&` operator for `Sink`, which composes two sinks
           * together. Implement it in a way that ensures the following unit test
           * succeeds.
           */
          test("&&") {
            val summer = Sink[Int, Int](_.foldLeft[Int](0)(_ + _))

            val stream = Stream(1, 2, 3, 4, 5)

            val result = (summer && summer).run(stream)

            assertTrue(result == (15, 15))
          } @@ ignore +
            /**
             * EXERCISE
             *
             * Implement the `Sink#map` method in such a way as to make the
             * following unit test pass.
             */
            test("map") {
              val summer = Sink[Int, Int](_.foldLeft[Int](0)(_ + _))

              val stream = Stream(1, 2, 3, 4, 5)

              val result = summer.map(_.toString).run(stream)

              assertTrue(result == "15")
            } @@ ignore
        } +
          suite("constructors") {

            /**
             * EXERCISE
             *
             * Implement the `Sink.sum` constructor in such a way as to make the
             * following unit test pass.
             */
            test("sum") {
              val sink = Sink.sum[Int]

              val stream = Stream(1, 2, 3, 4, 5)

              val result = sink.run(stream)

              assertTrue(result == 15)
            } @@ ignore +
              /**
               * EXERCISE
               *
               * Implement the `Sink.fold` constructor in such a way as to make the
               * following unit test pass.
               */
              test("fold") {
                val sink = Sink.fold[Int, Int](0)(_ + _)

                val stream = Stream(1, 2, 3, 4, 5)

                val result = sink.run(stream)

                assertTrue(result == 15)
              } @@ ignore
          }
      }
  }
}
