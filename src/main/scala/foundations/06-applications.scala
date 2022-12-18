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

object StreamApp extends ZIOAppDefault {
  def run = Console.printLine("Hello World!")
}
