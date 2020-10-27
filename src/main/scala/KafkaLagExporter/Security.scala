/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package KafkaLagExporter

import java.util.Properties

import com.google.cloud.secretmanager.v1.{SecretManagerServiceClient, SecretVersionName}
import javax.annotation.concurrent.NotThreadSafe

import scala.util.Try

@NotThreadSafe
object Security {

  private var _gsmCli: SecretManagerServiceClient = null

  def apply(configKey: String, availableConfigs: Map[String, Map[String, Map[String, String]]]): Properties = {
    if (configKey.isEmpty) {
      return new Properties()
    }
    val securitySource = configKey.split(":")
    if (securitySource.length != 2) {
      throw new IllegalArgumentException(String.format("config key: %s is not in format %s", configKey, "some_source:development"))
    }

    val c = Try(availableConfigs(securitySource(0))(securitySource(1))).orElse(throw new IllegalArgumentException(String.format("combination %s does not exist", configKey)))

    try securitySource(0).toLowerCase() match {
      case "gsm" => gsm(configKey, c.get)
      case _ => throw new IllegalArgumentException(String.format("unknown security source %s", securitySource(0)))
    } catch {
      case e: Exception => throw new Exception(String.format("security retrieval of with secret config: %s, failed", configKey), e)
    }
  }

  def gsm(configKey: String, opts: Map[String, String]): Properties = {
    if (!opts.contains("projectId") || !opts.contains("secretId")) {
      throw new IllegalArgumentException(String.format("security config: %s does not contain all required fields", configKey))
    }
    if (_gsmCli == null) {
      _gsmCli = SecretManagerServiceClient.create()
    }
    val secretVersionName = SecretVersionName.of(opts("projectId"), opts("secretId"), "latest")
    val res = _gsmCli.accessSecretVersion(secretVersionName)
    val content = res.getPayload.getData.toStringUtf8.split("\n")

    if (content.length != 2) {
      throw new IllegalArgumentException("response from secret manager is incorrect")
    }

    val p = new Properties()
    p.put("sasl.jaas.config", String.format(
      "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", content(0), content(1)))
    p.put("ssl.endpoint.identification.algorithm", "https")
    p.put("security.protocol", "SASL_SSL")
    p.put("sasl.mechanism", "PLAIN")

    p
  }

  def close(): Unit = {
    if (_gsmCli != null) {
      _gsmCli.close()
      _gsmCli = null
    }
  }
}
