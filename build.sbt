enablePlugins(JavaAppPackaging)

name := "jev-word-stream"

scalaVersion := "3.9.0"

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
  "com.jamesward" %% "zio-evals" % "0.1.2" % Test,
)

fork := true

Compile / mainClass := Some("Main")

// Local-only development MCP server. It remains loopback-bound and the plugin
// automatically disables it in CI. `sbt-task` can execute arbitrary sbt tasks.
mcpEnabled := true
mcpHost := "127.0.0.1"
mcpPort := 5015

addCommandAlias("dev", "~runReload")

// Agent Skills are extracted for coding agents and stay off the application classpath.
skillsJarsOutputDir := Some(file(".kiro/skills"))
libraryDependencies += "com.jamesward" % "skills" % "0.0.3" % Skills
