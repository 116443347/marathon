import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper.directory
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import mesosphere.maven.MavenSettings.{loadM2Credentials, loadM2Resolvers}
import mesosphere.raml.RamlGeneratorPlugin
import sbtprotobuf.ProtobufPlugin
import scalariform.formatter.preferences._

credentials ++= loadM2Credentials(streams.value.log)
resolvers ++= loadM2Resolvers(sLog.value)

resolvers += Resolver.sonatypeRepo("snapshots")

addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")
addCompilerPlugin(scalafixSemanticdb)

val silencerVersion = "1.1"
addCompilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion)
libraryDependencies += "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided

lazy val formatSettings = Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(DoubleIndentConstructorArguments, true)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpacesAroundMultiImports, false)
)

// Pass arguments to Scalatest runner:
// http://www.scalatest.org/user_guide/using_the_runner
lazy val testSettings = Seq(
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testListeners := Nil, // TODO(MARATHON-8215): Remove this line
  testOptions in Test := Seq(
    Tests.Argument(
      "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-y", "org.scalatest.WordSpec")),
  fork in Test := true
)

// Pass arguments to Scalatest runner:
// http://www.scalatest.org/user_guide/using_the_runner
lazy val integrationTestSettings = Seq(
  testListeners := Nil, // TODO(MARATHON-8215): Remove this line

  fork in Test := true,
  testOptions in Test := Seq(
    Tests.Argument(
      "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  concurrentRestrictions in Test := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2))),
  javaOptions in (Test, test) ++= Seq(
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
    "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
    "-Dscala.concurrent.context.minThreads=2",
    "-Dscala.concurrent.context.maxThreads=32"
  ),
  concurrentRestrictions in Test := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)))
)

// Build Settings for Protobuf (https://github.com/sbt/sbt-protobuf)
//
// version => The version of the protobuf library to be used. An sbt dependency is added for the project
// includeFilter => Specify which files to compile. We need this to exclude the mesos/mesos.proto to be compiled directly
// protobufRunProtoc => Use ProtocJar to use a bundled protoc version, so we don't rely on a preinstalled version. "-v330" defines the protoc version
val pbSettings = ProtobufPlugin.projectSettings ++ Seq(
  (version in ProtobufConfig) := "3.3.0",
  (includeFilter in ProtobufConfig) := "marathon.proto",
  (protobufRunProtoc in ProtobufConfig) := (args => com.github.os72.protocjar.Protoc.runProtoc("-v330" +: args.toArray))
)

lazy val commonSettings = Seq(
  autoCompilerPlugins := true,
  organization := "mesosphere.marathon",
  scalaVersion := "2.12.10",
  crossScalaVersions := Seq(scalaVersion.value),
  scalacOptions in Compile ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfuture",
    "-Xlint",
    //FIXME: CORE-977 and MESOS-7368 are filed and need to be resolved to re-enable this
    // "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Yrangepos",
    "-Ywarn-numeric-widen",
    //"-Ywarn-dead-code", We should turn this one on soon
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused-import",
    "-Ywarn-unused:-locals,imports",
    //"-Ywarn-value-discard", We should turn this one on soon.
  ),
  // Don't need any linting, etc for docs, so gain a small amount of build time there.
  scalacOptions in (Compile, doc) := Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-Xfuture"),
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    Resolver.JCenterRepository,
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Apache Shapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Mesosphere Public Repo" at "https://downloads.mesosphere.com/maven",
    "Mesosphere Snapshot Repo" at "https://downloads.mesosphere.com/maven-snapshot"
  ),
  cancelable in Global := true,
  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := DefaultAWSCredentialsProviderChain.getInstance(),
  s3region :=  com.amazonaws.services.s3.model.Region.US_Standard,

  fork in run := true
)

/**
  * The documentation for sbt-native-package can be foound here:
  * - General, non-vendor specific settings (such as launch script):
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  *
  * - Linux packaging settings
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  */
lazy val packagingSettings = Seq(
  bashScriptExtraDefines += IO.read((baseDirectory.value / "project" / "NativePackagerSettings" / "extra-defines.bash")),
  mappings in (Compile, packageDoc) := Seq(),

  (packageName in Universal) := {
    import sys.process._
    val shortCommit = ("./version commit" !!).trim
    s"${packageName.value}-${version.value}-$shortCommit"
  },

  /* Universal packaging (docs) - http://sbt-native-packager.readthedocs.io/en/latest/formats/universal.html
   */
  universalArchiveOptions in (UniversalDocs, packageZipTarball) := Seq("-pcvf"), // Remove this line once fix for https://github.com/sbt/sbt-native-packager/issues/1019 is released
  (packageName in UniversalDocs) := {
    import sys.process._
    val shortCommit = ("./version commit" !!).trim
    s"${packageName.value}-docs-${version.value}-$shortCommit"
  },
  (topLevelDirectory in UniversalDocs) := { Some((packageName in UniversalDocs).value) },
  mappings in UniversalDocs ++= directory("docs/docs"),

  maintainer := "Mesosphere Package Builder <support@mesosphere.io>")

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
    .settings(testSettings : _*)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      version := {
        import sys.process._
        ("./version" !!).trim
      },
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging,
    RamlGeneratorPlugin, BasicLintingPlugin, GitVersioning, ProtobufPlugin)
  .dependsOn(`plugin-interface`)
  .settings(pbSettings)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(packagingSettings: _*)
  .settings(
    version := {
      import sys.process._
      ("./version" !!).trim
    },
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    sourceGenerators in Compile += (ramlGenerate in Compile).taskValue,
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )

lazy val ammonite = (project in file("./tools/repl-server"))
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(
    mainClass in Compile := Some("ammoniterepl.Main"),
    libraryDependencies += "com.lihaoyi" % "ammonite-sshd" % "2.0.4" cross CrossVersion.full
  )
  .dependsOn(marathon)

lazy val integration = (project in file("./tests/integration"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
  .settings(integrationTestSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(
    cleanFiles += baseDirectory { base => base / "sandboxes" }.value
  )
  .dependsOn(marathon % "test->test")

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .enablePlugins(GitBranchPrompt, BasicLintingPlugin)
  .settings(testSettings : _*)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .enablePlugins(JmhPlugin, GitBranchPrompt, BasicLintingPlugin)
  .settings(testSettings : _*)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark
  )
