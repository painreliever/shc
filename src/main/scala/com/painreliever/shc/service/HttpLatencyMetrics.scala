package com.painreliever.shc.service

trait HttpLatencyMetricsReporter {
  def apply(service: String, urlTemplate: String, latencyInSeconds: Double): Unit
}

class NoLatencyReporter extends HttpLatencyMetricsReporter {
  override def apply(service: String, urlTemplate: String, latencyInSeconds: Double): Unit = {}
}
