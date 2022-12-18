# Overview

Learn the algebra of streaming, and gain an ability to solve virtually any problem in data engineering, data pipelining, complex event sourcing, or streaming with the power of a few simple compositional operators. In this workshop, you will build your own streaming library, exploring the foundational basis of both push and pull-based streaming models, with a focus on purely functional design.

# Who Should Attend

Scala developers who are struggling to leverage concurrent streaming for solving problems involving event sourcing, microservices, web APIs, and queuing solutions such as Pulsar and Kafka.

# Prerequisites

Good working knowledge of Scala, including familiarity with immutable data, pattern matching, and basic recursion. Developers who have attended Functional Scala Foundations will be well-prepared for this course.

# Topics

 - Everything-as-a-stream
 - Resourceful streams
 - Push-based streams
 - Pull-based streams
 - Concurrent streaming operators
 - Converting push- and pull-based streams
 - Fan in and fan out streams
 - Batching to improve performance

# Daily Structure

Three days, 7 hours a day starting at 09:00 London Time, until 16:00 London Time.

# Attendance

Attendance at this workshop is fully remote. Attendees will be provided with a link to a remote meeting session the day before the event, in which they can see and hear the workshop, ask the instructor questions, and chat with other attendees. No preparation is required, simply bring your laptop with text editor, Scala, and SBT installed.

# Usage

## From the UI

1. Download the repository as a [zip archive](https://github.com/jdegoes/foundations-streaming/archive/master.zip).
2. Unzip the archive, usually by double-clicking on the file.
3. Configure the source code files in the IDE or text editor of your choice.

## From the Command Line

1. Open up a terminal window.

2. Clone the repository.

    ```bash
    git clone https://github.com/jdegoes/foundations-streaming
    ```
5. Launch project provided `sbt`.

    ```bash
    cd foundations-streaming; ./sbt
    ```
6. Enter continuous compilation mode.

    ```bash
    sbt:foundations-streaming> ~ test:compile
    ```

Hint: You might get the following error when starting sbt:

> [error] 	typesafe-ivy-releases: unable to get resource for com.geirsson#sbt-scalafmt;1.6.0-RC4: res=https://repo.typesafe.com/typesafe/ivy-releases/com.geirsson/sbt-scalafmt/1.6.0-RC4/jars/sbt-scalafmt.jar: javax.net.ssl.SSLHandshakeException: sun.security.validator.ValidatorException: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested targe

It's because you have an outdated Java version, missing some newer certificates. Install a newer Java version, e.g. using [Jabba](https://github.com/shyiko/jabba), a Java version manager. See [Stackoverflow](https://stackoverflow.com/a/58669704/1885392) for more details about the error.

# Legal

Copyright&copy; 2019-2022 John A. De Goes. All rights reserved.
