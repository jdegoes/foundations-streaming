/**
 * STREAMS
 *
 * The raison d'etre of streams is their ability to do _incremental
 * computation_.  Ordinarily, functions instantly compute their
 * singular result. The stream paradigm, however, allows streams to
 * produce results incrementally.
 *
 * Importantly for modern applications, which process never-ending
 * amounts of data (events, requests, etc.), proper streaming toolkits
 * allow us to incrementally perform computation over infinite streams
 * of data, in bounded memory, without resource leaks, and with the
 * right degree of concurrency for every job.
 *
 * There are many streaming libraries in Scala, including Akka Streams,
 * FS2, ZIO Streams, etc. This course will teach you the foundations
 * that underpin these libraries and other libraries in other
 * ecosystems. By the time you are done with this workshop, you will
 * have generic knowledge about the nature of streaming that will
 * enable to you use and be proficient with any streaming library.
 */
package foundations.streams.intro

import zio._
import zio.test._
import zio.test.TestAspect._
import scala.annotation.tailrec

/**
 * STREAMS
 *
 * The requirement that streams be able to operate on unbounded amounts of
 * ddata means that streams must be lazy. In turn, laziness means that the
 * interface of a stream cannot be exactly like that of a Scala collection.
 * That said, most collection methods have an equivalent in the world of
 * streaming.
 *
 * In this section, you will explore streams as a "lazy version of List",
 * which is the simplest possible mental model, and allows you to leverage
 * your knowledge of Scala collections to understand streams.
 */
object SimpleStream extends ZIOSpecDefault {
  sealed trait Stream[+A] {
    final def map[B](f: A => B): Stream[B] = ???

    final def take(n: Int): Stream[A] = ???

    final def drop(n: Int): Stream[A] = ???

    final def filter(f: A => Boolean): Stream[A] = ???

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] = ???

    final def flatMap[B](f: A => Stream[B]): Stream[B] = ???

    final def mapAccum[S, B](initial: S)(f: (S, A) => (S, B)): Stream[B] = ???

    final def foldLeft[B](initial: B)(f: (B, A) => B): B = ???

    final def runCollect: Chunk[A] =
      this match {
        case Stream.Empty            => Chunk.empty
        case Stream.Cons(head, tail) => Chunk.single(head()) ++ tail().runCollect
      }
  }
  object Stream {
    case object Empty                                               extends Stream[Nothing]
    final case class Cons[+A](head: () => A, tail: () => Stream[A]) extends Stream[A]

    def apply[A](as: A*): Stream[A] = {
      def loop(list: List[A]): Stream[A] =
        list match {
          case Nil          => Empty
          case head :: tail => Cons(() => head, () => loop(tail))
        }

      loop(as.toList)
    }

    def unfold[S, A](initial: S)(f: S => Option[S]): Stream[S] = ???

    def iterate[S](initial: S)(f: S => S): Stream[S] = unfold(initial)(s => Some(f(s)))
  }

  def spec = suite("SimpleStream") {
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
            taken <- ZIO.succeed(stream.take(2))
          } yield assertTrue(taken.runCollect == Chunk(1, 2))
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
            dropped <- ZIO.succeed(stream.drop(2))
          } yield assertTrue(dropped.runCollect == Chunk(3, 4))
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
            filtered <- ZIO.succeed(stream.filter(_ % 2 == 0))
          } yield assertTrue(filtered.runCollect == Chunk(2, 4))
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
            appended <- ZIO.succeed(stream1 ++ stream2)
          } yield assertTrue(appended.runCollect == Chunk(1, 2, 3, 4, 5, 6, 7, 8))
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
            flatMapped <- ZIO.succeed(stream.flatMap(a => Stream(a, a)))
          } yield assertTrue(flatMapped.runCollect == Chunk(1, 1, 2, 2, 3, 3, 4, 4))
        } @@ ignore +
        /**
         * EXERCISE
         *
         * Interpret the meaning of the `flatMap` operation for streams.
         */
        test("meaning of flatMap") {
          final case class User(id: String, name: String, age: Int)
          val userIdData = List("jdegoes", "jdoe", "msmith")
          val database = Map(
            "jdegoes" -> User("jdegoes", "John De Goes", 43),
            "jdoe"    -> User("jdoe", "John Doe", 30),
            "msmith"  -> User("msmith", "Mary Smith", 25)
          )
          def lookupUser(id: String): Stream[User] =
            Stream(database.get(id).toList: _*)

          val userIds = Stream(userIdData: _*)

          val streamProgram = for {
            id   <- userIds
            user <- lookupUser(id)
          } yield user

          val result = streamProgram.runCollect

          assertTrue(
            result == Chunk(
              User("jdegoes", "John De Goes", 43),
              User("jdoe", "John Doe", 30),
              User("msmith", "Mary Smith", 25)
            )
          )
        } @@ ignore +
        /**
         * EXERCISE
         *
         * Implement the `Stream#mapAccum` method in such a way as to make this test
         * case pass.
         */
        test("mapAccum") {
          val stream = Stream(1, 2, 3, 4)

          for {
            mapped <- ZIO.succeed(stream.mapAccum(0)((acc, a) => (acc + a, acc + a)))
          } yield assertTrue(mapped.runCollect == Chunk(1, 3, 6, 10))
        } @@ ignore +
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
        } @@ ignore
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
      }
  }
}

/**
 * RESOURCES
 *
 * One of the most delightful features of well-constructed streaming toolkits
 * is that they automatically take care of resources for you. This means you
 * do not explicitly open or close resources, as these operations are baked
 * into streams that access resourceful contents (such as sockets, files,
 * persistent queues, database result sets, etc.).
 *
 *
 */
object ResourcefulStream extends ZIOSpecDefault {
  sealed trait Stream[+A] {
    final def map[B](f: A => B): Stream[B] = ???

    final def ++[A1 >: A](that: => Stream[A1]): Stream[A1] = ???

    final def ensuring(finalizer: => Unit): Stream[A] = Stream.Ensuring(this, () => finalizer)

    final def flatMap[B](f: A => Stream[B]): Stream[B] = ???

    final def runCollect: Chunk[A] = {
      @tailrec
      def loop[A](streams: List[(Stream[A], List[() => Unit])], acc: Chunk[A]): Chunk[A] =
        streams match {
          case Nil => acc

          case (Stream.Empty, finalizers) :: rest =>
            finalizers.map(f => scala.util.Try(f()))

            loop(rest, acc)

          case (Stream.Cons(head, tail), finalizers) :: rest =>
            loop((tail(), finalizers) :: rest, acc :+ head())

          case (Stream.Ensuring(stream, finalizer), finalizers) :: rest =>
            loop((stream, finalizer :: finalizers) :: rest, acc)
        }

      loop((this -> Nil) :: Nil, Chunk.empty)
    }
  }
  object Stream {
    case object Empty                                                       extends Stream[Nothing]
    final case class Cons[+A](head: () => A, tail: () => Stream[A])         extends Stream[A]
    final case class Ensuring[+A](stream: Stream[A], finalizer: () => Unit) extends Stream[A]

    def apply[A](as: A*): Stream[A] = {
      def loop(list: List[A]): Stream[A] =
        list match {
          case Nil          => Empty
          case head :: tail => Cons(() => head, () => loop(tail))
        }

      loop(as.toList)
    }

    def attempt[A](a: => A): Stream[A] =
      ???

    def suspend[A](stream: => Stream[A]): Stream[A] =
      ???

    def fromFile(file: String): Stream[Byte] = {
      import java.io.FileInputStream

      ???
    }
  }

  def spec = suite("ResourcefulStream") {

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
  }
}
