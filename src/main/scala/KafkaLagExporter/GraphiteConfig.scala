/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package KafkaLagExporter

case class GraphiteConfig(host: String, port: Int, prefix: Option[String])
