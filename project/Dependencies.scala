import sbt._

object Dependencies {
  private object Version {
    val akka = "2.6.4"
    val akkaHttp = "10.1.11"
    val slf4jApi = "1.7.30"

    val scalatest = "3.1.1"
  }

  val akkaActors = "com.typesafe.akka" %% "akka-actor" % Version.akka
  val akkaStreams = "com.typesafe.akka" %% "akka-stream" % Version.akka
  val akkaHttp = "com.typesafe.akka" %% "akka-http" % Version.akkaHttp
  val slf4jApi = "org.slf4j" % "slf4j-api" % Version.slf4jApi

  val scalatest = "org.scalatest" %% "scalatest" % Version.scalatest % Test
}
