import Dependencies._
import ReleaseTransformations._
import ReleasePlugin.autoImport._

import scala.sys.process._

assemblyJarName in assembly := "kafka-lag-exporter.jar"

// META-INF discarding
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case PathList("reference.conf") => MergeStrategy.concat
  case x => MergeStrategy.first
}

lazy val kafkaLagExporter =
  Project(id = "kafka-lag-exporter", base = file("."))
    .enablePlugins(AutomateHeaderPlugin)
    .enablePlugins(JavaAppPackaging)
    .settings(commonSettings)
    .settings(
      name := "kafka-lag-exporter",
      description := "Kafka lag exporter finds and reports Kafka consumer group lag metrics",
      libraryDependencies ++= Vector(
        LightbendConfig,
        Kafka,
        Akka,
        AkkaTyped,
        AkkaSlf4j,
        AkkaStreams,
        AkkaStreamsProtobuf,
        Fabric8Model,
        Fabric8Client,
        Prometheus,
        PrometheusHotSpot,
        PrometheusHttpServer,
        ScalaJava8Compat,
        Logback,
        ScalaTest,
        AkkaTypedTestKit,
        MockitoScala,
        AkkaStreamsTestKit,
        AlpakkaKafkaTestKit,
        Testcontainers,
        AkkaHttp,
        Gsm
      ),
      skip in publish := true,
      parallelExecution in Test := false,
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        runClean,
        runTest,
        packageJavaApp
      )
    )

lazy val commonSettings = Seq(
  organization := "com.lightbend.kafkalagexporter",
  scalaVersion := Version.Scala,
  scalacOptions ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-Xlog-reflective-calls",
    "-Xlint",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    "-deprecation",
    "-feature",
    "-language:_",
    "-unchecked"
  ),
  maintainer := "sean.glover@lightbend.com",
  scalacOptions in (Compile, console) := (scalacOptions in (Global)).value.filter(_ == "-Ywarn-unused-import"),
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  organizationName := "Lightbend Inc. <http://www.lightbend.com>",
  startYear := Some(2020),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
)

def exec(cmd: String, errorMessage: String): Unit = {
  val e = cmd.!
  if (e != 0) sys.error(errorMessage)
}

lazy val packageJavaApp = ReleaseStep(
  action = { st: State =>
    val extracted = Project.extract(st)
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(packageBin in Universal in ref, st)
  }
)