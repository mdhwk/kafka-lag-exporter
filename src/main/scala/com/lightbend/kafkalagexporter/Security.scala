/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafkalagexporter

import com.google.cloud.secretmanager.v1.{SecretManagerServiceClient, SecretVersionName}
import javax.annotation.concurrent.NotThreadSafe

// TODO: maybe throw some error if secopt is choosen but not containing all mandatory info
@NotThreadSafe
object Security {

  private var _gsmCli: SecretManagerServiceClient = null

  def apply(clusterSec: String, opts: Map[String, String]): Map[String, String] = {
    if (clusterSec.isEmpty) {
      return Map.empty
    }
    val shortName = clusterSec.split("\\.")(0)

    shortName.toLowerCase() match {
      case "gsm" => try gsm(clusterSec, opts) catch {
        case e: Exception => System.out.println(e.getMessage); Map.empty
      }
      case _ => Map.empty
    }
  }

  def gsm(clusterSec: String, opts: Map[String, String]): Map[String, String] = {
    if (!opts.contains(clusterSec + ".projectId") || !opts.contains(clusterSec + ".secretId")) {
      Map.empty
    }
    if (_gsmCli == null) {
      _gsmCli = SecretManagerServiceClient.create()
    }
    val secretVersionName = SecretVersionName.of(opts(clusterSec + ".projectId"), opts(clusterSec + ".secretId"), "latest")
    val res = _gsmCli.accessSecretVersion(secretVersionName)

    val content = res.getPayload.getData.toStringUtf8.split("\n")

    if (content.length < 2) {
      Map.empty
    }

    Map[String, String](
      "sasl.jaas.config" -> String.format(
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", content(0), content(1)),
      "ssl.endpoint.identification.algorithm" -> "https",
      "security.protocol" -> "SASL_SSL",
      "sasl.mechanism" -> "PLAIN",
    )
  }

  def close(): Unit = {
    if (_gsmCli != null) {
      _gsmCli.close()
      _gsmCli = null
    }
  }
}
