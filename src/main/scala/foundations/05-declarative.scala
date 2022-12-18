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

import zio._
import zio.stream._

import zio.test._
import zio.test.TestAspect._

object DeclarativeSpec extends ZIOSpecDefault {
  def spec =
    suite("DeclarativeSpec")()
}
