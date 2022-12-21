/**
 * APPLICATIONS
 *
 * In this section, you will explore a graduation project that leverages one
 * of the streaming libraries you built in order to solve a small but
 * realistic problem in streaming.
 */
package foundations.apps

import zio._

import zio.stream._
import java.io.IOException

/**
 * Build a command-line application that accepts a number of files.
 * Each file contains text. Perform a word-count operation on all
 * files in parallel, and then aggregate the word count information.
 * Finally, display the aggregated word count.
 */
object StreamApp extends ZIOAppDefault {
  final case class WordCount(map: Map[String, Int]) { self =>
    def ++(that: WordCount): WordCount =
      WordCount((self.map.keySet ++ that.map.keySet).foldLeft[Map[String, Int]](Map()) {
        case (map, key) =>
          val leftCount  = self.map.getOrElse(key, 0)
          val rightCount = that.map.getOrElse(key, 0)

          map.updated(key, leftCount + rightCount)
      })

    def count(word: String): WordCount =
      WordCount(map.updated(word, map.getOrElse(word, 0)))
  }
  object WordCount {
    def empty: WordCount = WordCount(Map())
  }

  def open(file: String): ZStream[Any, Throwable, Byte] =
    ZStream.fromFileName(file)

  val bytesToWords =
    ZPipeline.utf8Decode >>>
      ZPipeline.splitLines >>>
      ZPipeline.splitOn(" ") >>>
      ZPipeline.filter[String](_ != "")

  val counter: Sink[Nothing, String, Nothing, WordCount] =
    ZSink.foldLeft(WordCount.empty)(_.count(_))

  def runWordCount(file: String): ZStream[Any, Throwable, WordCount] =
    ZStream.fromZIO(open(file) >>> bytesToWords >>> counter)

  def getArgsStream: ZStream[ZIOAppArgs, Nothing, String] =
    ZStream.unwrap(getArgs.map(ZStream.fromChunk(_)))

  def run =
    getArgsStream
      .flatMapPar(100)(
        file =>
          runWordCount(file)
            .retry(Schedule.recurs(10) && Schedule.exponential(10.millis))
            .catchAllCause(_ => ZStream(WordCount.empty)) // Ignore all errors
      )
      .runFold(WordCount.empty)(_ ++ _)
}
