enablePlugins(JavaAppPackaging)

name := "jev-word-stream"

scalaVersion := "3.10.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
)

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-typesafe-ai" % "0.1.0",

  "org.webjars.npm" % "tailwindcss__browser" % "4.3.3" % WebJar,

  "dev.zio" %% "zio-test" % ("dev.zio" %% "zio").version % Test,
  "dev.zio" %% "zio-test-sbt" % ("dev.zio" %% "zio").version % Test,
  "com.jamesward" %% "zio-evals" % "0.2.0" % Test,
)

fork := true

Compile / mainClass := Some("Main")

addCommandAlias("dev", "~runReload")

// Agent Skills are extracted for coding agents and stay off the application classpath.
skillsJarsOutputDir := Some(file(".kiro/skills"))
libraryDependencies += "com.jamesward" % "skills" % "0.0.17" % Skills
