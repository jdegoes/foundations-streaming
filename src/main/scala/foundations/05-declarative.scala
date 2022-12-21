/**
 * DECLARATIVE
 *
 * The executable encodings you have looked at for streams so far are common
 * in Java, but less common in Scala. They have some major drawbacks, chiefly
 * of which is the fact that they are not stack-safe. It is possible to
 * overflow the stack by repeatedly applying any operator.
 *
 * In order to achieve stack-safety, as well as increase potential for
 * performance optimizations, most real-world streaming libraries use a
 * declarative encoding.
 *
 * In this section, you will explore a declarative encoding of streams.
 */
package foundations.declarative

// import zio._

object Experiment {
  import zio._

  sealed trait Stream[+A] { self =>
    import foundations.declarative.Experiment.Stream.{ Concat, Emit, Empty, Ensuring, Make }

    final def ::[A1 >: A](a: A1): Stream[A1] = Stream.Emit(a) ++ self

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] =
      Stream.Concat(self, Stream.suspend(that))

    final def drop(n: Int): Stream[A] =
      if (n <= 0) self
      else self.unconsToStreamWith(Stream.empty, (_, t) => t.drop(n - 1))

    final def ensuring(finalizer: UIO[Any]): Stream[A] =
      Stream.Ensuring(self, Stream.unwrap(finalizer.as(Stream.empty)))

    final def filter(f: A => Boolean): Stream[A] =
      self.flatMap(a => if (f(a)) Stream(a) else Stream.empty)

    final def flatMap[B](f: A => Stream[B]): Stream[B] =
      self match {
        case Empty                       => Empty
        case Make(make)                  => Stream.unwrap(make.map(_.flatMap(f)))
        case Concat(left, right)         => left.flatMap(f) ++ right.flatMap(f)
        case Emit(a)                     => f(a)
        case Ensuring(stream, finalizer) => Ensuring(stream.flatMap(f), finalizer)
      }

    final def fold[S](s: S)(f: (S, A) => S): ZIO[Any, Throwable, S] = {
      def loop(self: Stream[A], s: S): ZIO[Scope, Throwable, S] =
        self match {
          case Empty                       => ZIO.succeed(s)
          case Make(make)                  => make.flatMap(loop(_, s))
          case Concat(left, right)         => ZIO.scoped(loop(left, s)).flatMap(loop(right, _))
          case Emit(a)                     => ZIO.succeed(f(s, a))
          case Ensuring(stream, finalizer) => loop(stream, s).ensuring(finalizer.runDrain.orDie)
        }

      ZIO.scoped(loop(self, s))
    }

    final def foldZIO[S](s: S)(f: (S, A) => Task[S]): ZIO[Any, Throwable, S] = {
      def loop(self: Stream[A], s: S): ZIO[Scope, Throwable, S] =
        self match {
          case Empty                       => ZIO.succeed(s)
          case Make(make)                  => make.flatMap(loop(_, s))
          case Concat(left, right)         => loop(left, s).flatMap(loop(right, _))
          case Emit(a)                     => f(s, a)
          case Ensuring(stream, finalizer) => loop(stream, s).ensuring(finalizer.runDrain.orDie)
        }

      ZIO.scoped(loop(self, s))
    }

    final def map[B](f: A => B): Stream[B] =
      unconsToStreamWith(Stream.empty, (h, t) => f(h) :: t.map(f))

    final def mapAccum[S, B](s: S)(f: (S, A) => (S, B)): Stream[B] =
      Stream.unwrap {
        for {
          ref <- Ref.make(s)
        } yield
          self.mapZIO { a =>
            for {
              s       <- ref.get
              (s2, b) = f(s, a)
              _       <- ref.set(s2)
            } yield b
          }
      }

    final def mapZIO[B](f: A => Task[B]): Stream[B] =
      self.flatMap(a => Stream.unwrap(f(a).map(Stream(_))))

    final def merge[A1 >: A](that: Stream[A1]): Stream[A1] = {
      type MergeState = Fiber[Throwable, Option[(A1, Stream[A1])]]

      def unconsFork(stream: Stream[A1]): ZIO[Scope, Throwable, MergeState] =
        stream.uncons.fork

      def loop(left: Option[MergeState], right: Option[MergeState]): Stream[A1] =
        (left, right) match {
          case (None, None) => Stream.empty

          case (Some(lfiber), None) =>
            Stream.unwrap(lfiber.join.map {
              case None                 => Stream.empty
              case Some((lhead, ltail)) => Stream(lhead) ++ ltail
            })

          case (None, Some(rfiber)) =>
            Stream.unwrap(rfiber.join.map {
              case None                 => Stream.empty
              case Some((rhead, rtail)) => Stream(rhead) ++ rtail
            })

          case (Some(lfiber), Some(rfiber)) =>
            Stream.unwrap(
              lfiber.join.raceWith[Scope, Throwable, Throwable, Option[(A1, Stream[A1])], Stream[A1]](rfiber.join)(
                (leftDone, _) =>
                  leftDone.flatMap[Scope, Throwable, Stream[A1]] {
                    case None =>
                      ZIO.succeed(loop(None, Some(rfiber)))

                    case Some((lhead, ltail)) =>
                      for {
                        lfiber <- ltail.uncons.fork
                      } yield Stream(lhead) ++ loop(Some(lfiber), Some(rfiber))
                  },
                (rightDone, _) =>
                  rightDone.flatMap {
                    case None =>
                      ZIO.succeed(loop(Some(lfiber), None))

                    case Some((rhead, rtail)) =>
                      for {
                        rfiber <- rtail.uncons.fork
                      } yield Stream(rhead) ++ loop(Some(lfiber), Some(rfiber))
                  }
              )
            )
        }

      Stream.unwrap(for {
        lfiber <- self.uncons.fork
        rfiber <- that.uncons.fork
      } yield loop(Some(lfiber), Some(rfiber)))
    }

    final def mkString(sep: String): Task[String] =
      self.fold("") {
        case (acc, a) =>
          if (acc.nonEmpty) acc + sep + a.toString()
          else a.toString()
      }

    final def run[A1 >: A, B](sink: Sink[A1, B]): ZIO[Any, Throwable, B] = 
      ZIO.scoped(sink.run(self).map(_._1))

    final def runCollect: Task[Chunk[A]] =
      fold[Chunk[A]](Chunk.empty[A])(_ :+ _)

    final def runDrain: Task[Unit] = fold(())((_, _) => ())

    final def runLast: Task[Option[A]] =
      fold[Option[A]](None) {
        case (_, a) => Some(a)
      }

    final def take(n: Int): Stream[A] =
      if (n <= 0) Stream.empty
      else self.unconsToStreamWith(Stream.empty, (h, t) => h :: t.take(n - 1))

    final def transduce[A1 >: A, B](sink: Sink[A1, B]): Stream[B] =
      Stream.unwrap(sink.run(self).map {
        case (b, None)            => Stream(b)
        case (b, Some(leftovers)) => b :: leftovers.transduce(sink)
      })

    final def uncons: ZIO[Scope, Throwable, Option[(A, Stream[A])]] =
      self match {
        case Empty      => ZIO.none
        case Make(make) => make.flatMap(_.uncons)
        case Concat(left, right) =>
          left.uncons.flatMap {
            case None               => right.uncons
            case Some((head, tail)) => ZIO.some(head -> Stream.Concat(tail, right))
          }
        case Emit(value)                 => ZIO.some(value -> Stream.empty)
        case Ensuring(stream, finalizer) => ZIO.addFinalizer(finalizer.runDrain.orDie) *> stream.uncons
      }

    def unconsWith[Z](empty: Z, cons: (A, Stream[A]) => Z): ZIO[Scope, Throwable, Z] =
      unconsWithZIO(ZIO.succeed(empty), (h, t) => ZIO.succeed(cons(h, t)))

    def unconsToStreamWith[B](empty: Stream[B], cons: (A, Stream[A]) => Stream[B]): Stream[B] =
      Stream.unwrap(unconsWith(empty, cons))

    def unconsWithZIO[Z](
      empty: ZIO[Scope, Throwable, Z],
      cons: (A, Stream[A]) => ZIO[Scope, Throwable, Z]
    ): ZIO[Scope, Throwable, Z] =
      uncons.flatMap {
        case None               => empty
        case Some((head, tail)) => cons(head, tail)
      }

    final def zip[B](that: => Stream[B]): Stream[(A, B)] =
      Stream.unwrap(self.uncons.zip(that.uncons).map {
        case (None, _)                                    => Stream.empty
        case (_, None)                                    => Stream.empty
        case (Some((lhead, ltail)), Some((rhead, rtail))) => (lhead, rhead) :: ltail.zip(rtail)
      })
  }
  object Stream {
    private case object Empty                                                            extends Stream[Nothing]
    private final case class Make[+A](make: ZIO[Scope, Throwable, Stream[A]])            extends Stream[A]
    private final case class Ensuring[+A](stream: Stream[A], finalizer: Stream[Nothing]) extends Stream[A]
    private final case class Emit[+A](value: A)                                          extends Stream[A]
    private final case class Concat[+A](left: Stream[A], right: Stream[A])               extends Stream[A]

    def apply[A](as: A*): Stream[A] =
      as.foldRight[Stream[A]](empty)(_ :: _)

    val empty: Stream[Nothing] = Empty

    def fromZIO[A](zio: ZIO[Scope, Throwable, A]): Stream[A] =
      Stream.unwrap(zio.map(Stream(_)))

    def succeed[A](a: => A): Stream[A] = Stream.unwrap(ZIO.succeed(Stream(a)))

    def suspend[A](stream: => Stream[A]): Stream[A] = Make(ZIO.attempt(stream))

    def unwrap[A](make: ZIO[Scope, Throwable, Stream[A]]): Stream[A] = Make(make)
  }

  final case class Pipeline[-A, +B](run: Stream[A] => Stream[B]) { self =>
    def >>>[C](that: Pipeline[B, C]): Pipeline[A, C] =
      Pipeline(self.run.andThen(that.run))
  }
  final case class Sink[A, +B](run: Stream[A] => ZIO[Scope, Throwable, (B, Option[Stream[A]])])

}

import zio._
import zio.stream._

import zio.test._
import zio.test.TestAspect._
import java.{ util => ju }

object DeclarativeSpec extends ZIOSpecDefault {
  sealed trait Stream[+A] { self =>
    final def >>>[B](pipeline: Pipeline[A, B]): Stream[B] = pipeline.run(self)

    final def map[B](f: A => B): Stream[B] =
      unconsToStreamWith(Stream.empty, (h, t) => Stream.Cons(f(h), t.map(f)))

    final def take(n: Int): Stream[A] = ???

    final def drop(n: Int): Stream[A] = ???

    final def ensuring(finalizer: UIO[Any]): Stream[A] = Stream.Ensuring(self, finalizer)

    final def filter(f: A => Boolean): Stream[A] = ???

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] = ???

    final def flatMap[B](f: A => Stream[B]): Stream[B] = ???

    final def mapAccum[S, B](initial: S)(f: (S, A) => (S, B)): Stream[B] = ???

    final def fold[S](initial: S)(f: (S, A) => S): Task[S] =
      ZIO.scoped {
        foldZIO(initial) {
          case (acc, a) => ZIO.succeed(Some(f(acc, a)))
        }.map(_._1)
      }

    def foldZIO[S](initial: S)(f: (S, A) => Task[Option[S]]): ZIO[Scope, Throwable, (S, Option[Stream[A]])]

    def transduce[A1 >: A, B](sink: Sink[A1, B]): Stream[B] = ???

    final def runCollect: Task[Chunk[A]] =
      ZIO.scoped {
        foldZIO[Chunk[A]](Chunk.empty) {
          case (acc, a) => ZIO.succeed(Some(acc :+ a))
        }.map(_._1)
      }

    def unconsWith[Z](empty: Z, cons: (A, Stream[A]) => Z): ZIO[Scope, Throwable, Z] =
      unconsWithZIO(ZIO.succeed(empty), (h, t) => ZIO.succeed(cons(h, t)))

    def unconsToStreamWith[B](empty: Stream[B], cons: (A, Stream[A]) => Stream[B]): Stream[B] =
      Stream.unwrap(unconsWith(empty, cons))

    def unconsWithZIO[Z](
      empty: ZIO[Scope, Throwable, Z],
      cons: (A, Stream[A]) => ZIO[Scope, Throwable, Z]
    ): ZIO[Scope, Throwable, Z] =
      uncons.flatMap {
        case None               => empty
        case Some((head, tail)) => cons(head, tail)
      }

    def uncons: ZIO[Scope, Throwable, Option[(A, Stream[A])]] =
      self match {
        case Stream.Empty => ZIO.succeed(None)

        case Stream.Make(make) => make.flatMap(_.uncons)

        case Stream.Cons(head, tail) =>
          ZIO.succeed(Some(head -> tail))

        case Stream.Ensuring(stream, finalizer) =>
          for {
            _   <- ZIO.addFinalizer(finalizer)
            opt <- stream.uncons
          } yield opt
      }
  }
  object Stream {
    case object Empty extends Stream[Nothing] {
      type A = Nothing

      def foldZIO[S](initial: S)(f: (S, A) => Task[Option[S]]): ZIO[Scope, Throwable, (S, Option[Stream[A]])] =
        ZIO.succeed(initial -> None)
    }
    final case class Make[+A](make: ZIO[Scope, Throwable, Stream[A]]) extends Stream[A] {
      def foldZIO[S](initial: S)(f: (S, A) => Task[Option[S]]): ZIO[Scope, Throwable, (S, Option[Stream[A]])] =
        make.flatMap(_.foldZIO(initial)(f))
    }
    final case class Cons[+A](head: A, tail: Stream[A]) extends Stream[A] {
      def foldZIO[S](initial: S)(f: (S, A) => Task[Option[S]]): ZIO[Scope, Throwable, (S, Option[Stream[A]])] =
        f(initial, head).flatMap {
          case None          => ZIO.succeed(initial -> Some(tail))
          case Some(initial) => tail.foldZIO(initial)(f)
        }
    }
    final case class Ensuring[A](stream: Stream[A], finalizer: UIO[Any]) extends Stream[A] {
      def foldZIO[S](initial: S)(f: (S, A) => Task[Option[S]]): ZIO[Scope, Throwable, (S, Option[Stream[A]])] =
        ZIO.addFinalizer(finalizer) *> stream.foldZIO(initial)(f)
    }

    def apply[A](as: A*): Stream[A] =
      as.toList.foldRight[Stream[A]](Stream.empty) {
        case (a, acc) => Stream.Cons(a, acc)
      }

    def attempt[A](a: => A): Stream[A] =
      Stream.unwrap(ZIO.attempt(a).map(a => Stream(a)))

    val empty: Stream[Nothing] = Stream.Empty

    def fromFile(file: String): Stream[Byte] = ???

    def iterate[S](initial: S)(f: S => S): Stream[S] = unfold(initial)(s => Some(f(s)))

    def succeed[A](a: => A): Stream[A] =
      Stream.unwrap(ZIO.succeed(a).map(a => Stream(a)))

    def unfold[S, A](initial: S)(f: S => Option[S]): Stream[S] =
      Stream(initial) ++ {
        f(initial) match {
          case None    => Stream.empty
          case Some(s) => unfold(s)(f)
        }
      }

    def unwrap[A](make: ZIO[Scope, Throwable, Stream[A]]): Stream[A] =
      Stream.Make(make)
  }

  final case class Pipeline[-A, +B](run: Stream[A] => Stream[B]) { self =>
    def >>>[C](that: Pipeline[B, C]): Pipeline[A, C] =
      Pipeline(self.run.andThen(that.run))
  }
  object Pipeline {
    def splitOn(char: Char): Pipeline[String, String] = ???

    def utf8Decode: Pipeline[Byte, String] = ???
  }
  final case class Sink[A, +B](run: Stream[A] => ZIO[Scope, Throwable, (B, Option[Stream[A]])]) { self =>
    def flatMap[C](f: B => Sink[A, C]): Sink[A, C] =
      Sink { stream =>
        self.run(stream).flatMap {
          case (b, None) => f(b).run(Stream.empty)
          case (b, Some(stream)) =>
            f(b).run(stream)
        }
      }

    def map[C](f: B => C): Sink[A, C] = self.flatMap(a => Sink.succeed(f(a)))
  }
  object Sink {
    def collectN[A](n: Int): Sink[A, Chunk[A]] =
      if (n <= 0) Sink.succeed(Chunk.empty)
      else
        for {
          a  <- Sink.read[A]
          as <- collectN(n - 1)
        } yield Chunk(a) ++ as

    def leftover[A](l: Stream[A]): Sink[A, Unit] =
      Sink[A, Unit] { stream =>
        ZIO.succeed(() -> Some((l ++ stream)))
      }

    def read[A]: Sink[A, A] =
      Sink(
        s =>
          s.unconsWithZIO(
            ZIO.fail(new ju.NoSuchElementException("The stream was empty")),
            (a, s) => ZIO.succeed(a -> Some(s))
          )
      )

    def succeed[A, B](b: => B): Sink[A, B] =
      Sink(s => ZIO.succeed(b -> Some(s)))
  }

  def spec =
    suite("DeclarativeSpec") {
      suite("Stream") {

        /**
         * EXERCISE
         *
         * Implement the `Stream#fold` method in all subtypes so that tests
         * can begin passing.
         */
        test("foldLeft") {
          val stream = Stream(1, 2, 3, 4)

          for {
            chunk <- stream.runCollect
          } yield assertTrue(chunk == Chunk(1, 2, 3, 4))
        } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#map` method in such a way as to make this test
           * case pass.
           */
          test("map") {
            val stream = Stream(1, 2, 3, 4)

            for {
              mapped <- stream.map(_ + 1).runCollect
            } yield assertTrue(mapped == Chunk(2, 3, 4, 5))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#take` method in such a way as to make this test
           * case pass.
           */
          test("take") {
            val stream = Stream(1, 2, 3, 4)

            for {
              taken <- stream.take(2).runCollect
            } yield assertTrue(taken == Chunk(1, 2))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#drop` method in such a way as to make this test
           * case pass.
           */
          test("drop") {
            val stream = Stream(1, 2, 3, 4)

            for {
              dropped <- stream.drop(2).runCollect
            } yield assertTrue(dropped == Chunk(3, 4))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#filter` method in such a way as to make this test
           * case pass.
           */
          test("filter") {
            val stream = Stream(1, 2, 3, 4)

            for {
              filtered <- stream.filter(_ % 2 == 0).runCollect
            } yield assertTrue(filtered == Chunk(2, 4))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#++` method in such a way as to make this test
           * case pass.
           *
           * Study your implementation and describe what's suboptimal about it!
           */
          test("++") {
            val stream1 = Stream(1, 2, 3, 4)
            val stream2 = Stream(5, 6, 7, 8)

            for {
              appended <- (stream1 ++ stream2).runCollect
            } yield assertTrue(appended == Chunk(1, 2, 3, 4, 5, 6, 7, 8))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#flatMap` method in such a way as to make this test
           * case pass.
           */
          test("flatMap") {
            val stream = Stream(1, 2, 3, 4)

            for {
              flatMapped <- stream.flatMap(a => Stream(a, a)).runCollect
            } yield assertTrue(flatMapped == Chunk(1, 1, 2, 2, 3, 3, 4, 4))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#mapAccum` method in such a way as to make this test
           * case pass.
           */
          test("mapAccum") {
            val stream = Stream(1, 2, 3, 4).mapAccum(0)((acc, a) => (acc + a, acc + a))

            for {
              result <- stream.runCollect
            } yield assertTrue(result == Chunk(1, 3, 6, 10))
          } @@ ignore +
          /**
           * EXERCISE
           *
           * Implement the `Stream#transduce` method in such a way as to make this
           * test case pass.
           */
          test("transduce") {
            val stream = Stream(1, 2, 3, 4)

            for {
              result <- stream.transduce(Sink.collectN[Int](2)).runCollect
            } yield assertTrue(result == Chunk(Chunk(1, 2), Chunk(3, 4)))
          } @@ ignore

        /**
         * EXCERCISE
         *
         * Implement `Stream.fromFile` in such a way as to make this test case pass
         * without leaking any resources.
         */
        test("fromFile") {
          val stream = Stream.fromFile("build.sbt")

          for {
            results <- stream.runCollect
          } yield assertTrue(results.length > 0)
        } @@ ignore
      } +
        suite("Pipeline") {

          /**
           * EXERCISE
           *
           * Implement `Pipeline.splitOn` to make the following test case pass.
           */
          test("splitOn") {
            val stream = Stream("Hello World!", "Goodbye!")

            for {
              result <- (stream >>> Pipeline.splitOn(' ')).runCollect
            } yield assertTrue(result == Chunk("Hello", "World!", "Goodbye!"))
          } @@ ignore +
            /**
             * EXERCISE
             *
             * Implement `Pipeline.utf8Decode` to make the following test
             * case pass.
             */
            test("utf8Decode") {
              val bytes = Chunk.fromArray("Hello World!".getBytes("UTF-8"))

              for {
                result <- (Stream(bytes: _*) >>> Pipeline.utf8Decode).runCollect
              } yield assertTrue(result.mkString("") == "Hello World!")
            } @@ ignore
        }
    }
}
