package io.rsocket.transport.akka.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.netty.buffer.ByteBufAllocator
import io.rsocket.fragmentation.FragmentationDuplexConnection
import io.rsocket.transport.ServerTransport
import io.rsocket.transport.ServerTransport.ConnectionAcceptor
import io.rsocket.transport.akka.WebsocketDuplexConnection
import reactor.core.publisher.{Mono, UnicastProcessor}

import scala.compat.java8.FutureConverters._
import scala.compat.java8.FunctionConverters._

class WebsocketServerTransport(val interface: String, val port: Int)(implicit system: ActorSystem, m: Materializer)
  extends ServerTransport[HttpServerBindingCloseable] {
  override def start(acceptor: ConnectionAcceptor, mtu: Int): Mono[HttpServerBindingCloseable] = {
    val isError = FragmentationDuplexConnection.checkMtu[HttpServerBindingCloseable](mtu)
    if (isError != null) {
      isError
    } else {
      val binding = Http().bind(interface, port)
        .to(Sink.foreach(conn => {
          val processor = UnicastProcessor.create[Message]
          conn.handleWith(
            handleWebSocketMessages(
              Flow.fromSinkAndSourceMat(Sink.asPublisher[Message](fanout = false), Source.fromPublisher(processor))
              ((in, _) => {
                val connection =
                  if (mtu > 0) {
                    new FragmentationDuplexConnection(
                      new WebsocketDuplexConnection(in, processor),
                      ByteBufAllocator.DEFAULT,
                      mtu,
                      false,
                      "server")
                  } else {
                    new WebsocketDuplexConnection(in, processor)
                  }
                acceptor.apply(connection).subscribe()
              })))
        }))
        .run()

      Mono.fromCompletionStage(binding.toJava).map(asJavaFunction(HttpServerBindingCloseable(_)))
    }
  }
}
