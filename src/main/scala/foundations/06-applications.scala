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

/**
 * Build a command-line application that accepts a number of files.
 * Each file contains text. Perform a word-count operation on all
 * files in parallel, and then aggregate the word count information.
 * Finally, display the aggregated word count.
 */
object StreamApp extends ZIOAppDefault {
  def run = Console.printLine("Hello World!")
}
