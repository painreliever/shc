package com.painreliever.shc

import org.scalatest.wordspec.AnyWordSpec
import TestAkka._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import HttpClient._

class HttpClientSpec extends AnyWordSpec {

  "HttpClient" should {
    "work with get requests" in {
      val client = new HttpClient("google", 10.seconds)
      val result = Await.result(client.get[String](url"https://www.google.com"), 10.seconds)
      println(result)
    }
  }

}
