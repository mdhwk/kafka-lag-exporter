/*
/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package KafkaLagExporter.integration

import akka.actor.typed.ActorSystem
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings
import akka.kafka.testkit.scaladsl.{ScalatestKafkaSpec, TestcontainersKafkaPerClassLike}
import KafkaLagExporter.{KafkaClusterManager, MainApp}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

abstract class SpecBase(val exporterPort: Int)
  extends ScalatestKafkaSpec(-1)
    with WordSpecLike
    with BeforeAndAfterEach
    with TestcontainersKafkaPerClassLike
    with Matchers
    with ScalaFutures
    with Eventually
    with PrometheusUtils
    with LagSim {

  implicit val patience: PatienceConfig = PatienceConfig(30 seconds, 2 second)

  override val testcontainersSettings = KafkaTestkitTestcontainersSettings(system)
    .withConfigureKafka { brokerContainers =>
      brokerContainers.foreach(_.withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"))
    }

  var kafkaLagExporter: ActorSystem[KafkaClusterManager.Message] = _

  val clusterName = "default"

  def config: Config = ConfigFactory.parseString(s"""
                                            |kafka-lag-exporter {
                                            |  reporters.prometheus.port = $exporterPort
                                            |  clusters = [
                                            |    {
                                            |      name: "$clusterName"
                                            |      bootstrap-brokers: "localhost:$kafkaPort"
                                            |    }
                                            |  ]
                                            |  poll-interval = 5 seconds
                                            |  lookup-table-size = 20
                                            |}""".stripMargin).withFallback(ConfigFactory.load())

  override def beforeEach(): Unit = {
    kafkaLagExporter = MainApp.start(config)
  }

  override def afterEach(): Unit = {
    kafkaLagExporter ! KafkaClusterManager.Stop
    Await.result(kafkaLagExporter.whenTerminated, 15 seconds)
  }
}
*/
