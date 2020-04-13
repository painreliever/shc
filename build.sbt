import sbt._
import Dependencies._

lazy val basicSettings = Seq(
  organization := "com.painreliever",
  description := "easy to use akka based http client library",
  startYear := Some(2020),
  shellPrompt := { s =>
    s"${Project.extract(s).currentProject.id} > "
  },
  version := "0.1",
  scalaVersion := "2.13.1",
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
  ),
  logLevel := Level.Info
)

lazy val root = (Project("shc", file("."))
  settings (moduleName := "shc", name := "shc")
  settings basicSettings
  settings (libraryDependencies ++= Seq(akkaActors, akkaStreams, akkaHttp, slf4jApi))
  settings (libraryDependencies ++= Seq(scalatest))
)
