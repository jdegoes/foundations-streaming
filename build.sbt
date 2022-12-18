val ZIOVersion = "2.0.5"

lazy val root = project
  .in(file("."))
  .settings(
    name := "foundations-streaming",
    organization := "net.degoes",
    scalaVersion := "2.13.10"
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias(
  "check",
  "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck"
)

libraryDependencies ++= Seq(
  // ZIO
  "dev.zio" %% "zio"          % ZIOVersion,
  "dev.zio" %% "zio-streams"  % ZIOVersion,
  "dev.zio" %% "zio-test"     % ZIOVersion,
  "dev.zio" %% "zio-test"     % ZIOVersion % "test",
  "dev.zio" %% "zio-test-sbt" % ZIOVersion % "test"
)

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
