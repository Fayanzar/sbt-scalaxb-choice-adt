inThisBuild(List(
  organization := "art.fayanzar",
  homepage := Some(url("https://github.com/Fayanzar/sbt-scalaxb-choice-adt")),
  // Alternatively License.Apache2 see https://github.com/sbt/librarymanagement/blob/develop/core/src/main/scala/sbt/librarymanagement/License.scala
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "fayanzar",
      "Dmytro Yakymets",
      "fayanzar@gmail.com",
      url("https://fayanzar.art")
    )
  )
))

sbtPlugin := true
name := "sbt-scalaxb-choice-adt"
organization := "art.fayanzar"

scalaVersion := "2.12.21"

libraryDependencies += "org.scalaxb" %% "scalaxb" % "1.12.5"

scalacOptions ++= Seq("-deprecation", "-feature")
