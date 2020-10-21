/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import java.util.concurrent.Executors

import akka.actor.typed.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object MainApp extends App {
  val system = start()

  // Add shutdown hook to respond to SIGTERM and gracefully shutdown the actor system
  sys.ShutdownHookThread {
    system ! KafkaClusterManager.Stop
    Await.result(system.whenTerminated, 10 seconds)
  }

  def start(config: Config = ConfigFactory.load()): ActorSystem[KafkaClusterManager.Message] = {
    // Cached thread pool for various Kafka calls for non-blocking I/O
    val kafkaClientEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val appConfig = AppConfig(config)

    val clientCreator = (cluster: KafkaCluster) =>
      KafkaClient(cluster, appConfig.clientGroupId, appConfig.clientTimeout, Security(cluster.securityOpt, appConfig.securityOpts))(kafkaClientEc)

    Security.close()
    var endpointCreators: List[KafkaClusterManager.NamedCreator] = List()
    appConfig.prometheusConfig.foreach { prometheus =>
      val prometheusCreator = KafkaClusterManager.NamedCreator(
        "prometheus-lag-reporter",
        (() => PrometheusEndpointSink(
          Metrics.definitions, appConfig.metricWhitelist, appConfig.clustersGlobalLabels(), new HTTPServer(prometheus.port), CollectorRegistry.defaultRegistry
        ))
      )
      endpointCreators = prometheusCreator :: endpointCreators
    }
    appConfig.graphiteConfig.foreach { _ =>
      val graphiteCreator = KafkaClusterManager.NamedCreator(
        "graphite-lag-reporter",
        (() => GraphiteEndpointSink(appConfig.metricWhitelist, appConfig.clustersGlobalLabels(), appConfig.graphiteConfig)))
      endpointCreators = graphiteCreator :: endpointCreators
    }
    ActorSystem(
      KafkaClusterManager.init(appConfig, endpointCreators, clientCreator), "kafka-lag-exporter")
  }
}
