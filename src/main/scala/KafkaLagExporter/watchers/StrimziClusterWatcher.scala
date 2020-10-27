/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package KafkaLagExporter.watchers

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import KafkaLagExporter.{KafkaCluster, KafkaClusterManager}
import KafkaLagExporter.KafkaClusterManager.Message

object StrimziClusterWatcher {
  val name: String = "strimzi"

  def init(handler: ActorRef[Message]): Behavior[Watcher.Message] = Behaviors.setup { context =>
    val watcher = new Watcher.Events {
      override def added(cluster: KafkaCluster): Unit = handler ! KafkaClusterManager.ClusterAdded(cluster)
      override def removed(cluster: KafkaCluster): Unit = handler ! KafkaClusterManager.ClusterAdded(cluster)
      override def error(e: Throwable): Unit = context.log.error(e.getMessage, e)
    }
    val client = StrimziClient(watcher)
    watch(client)
  }

  def watch(client: Watcher.Client): Behaviors.Receive[Watcher.Message] = Behaviors.receive {
    case (context, _: Watcher.Stop) =>
      Behaviors.stopped { () =>
        client.close()
        context.log.info("Gracefully stopped StrimziKafkaWatcher")
      }
  }
}
