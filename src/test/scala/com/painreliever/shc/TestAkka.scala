package com.painreliever.shc

import akka.actor.ActorSystem

object TestAkka {
  implicit val system: ActorSystem = ActorSystem("test")
}
