/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package KafkaLagExporter

import org.scalatest._

class GraphiteEndpointSinkTest extends fixture.FreeSpec with Matchers {

  class GraphiteServer extends Thread {
    import java.net.ServerSocket
    import java.io._
    val server = new ServerSocket(0)
    var line = ""
    override def run() : Unit = {
      val connection = server.accept
      val input = new BufferedReader(new InputStreamReader(connection.getInputStream))
      line = input.readLine()
      server.close();
    }
  }

  case class Fixture(server: GraphiteServer)
  type FixtureParam = Fixture

  override def withFixture(test: OneArgTest): Outcome = {
    val server = new GraphiteServer()
    server.start()
    test(Fixture(server))
  }

  "GraphiteEndpointSinkImpl should" - {

    "report only metrics which match the regex" in { fixture =>
      val sink = GraphiteEndpointSink(List("kafka_consumergroup_group_max_lag"), Map("cluster" -> Map.empty),
        Some(GraphiteConfig("localhost", fixture.server.server.getLocalPort(), None)))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupOffsetLagMetric, "cluster", "group", 100))
      sink.report(Metrics.GroupValueMessage(Metrics.MaxGroupTimeLagMetric, "cluster", "group", 1))
      fixture.server.join()
      fixture.server.line should startWith ("cluster.group.kafka_consumergroup_group_max_lag 100.0 ")
    }

  }

}

