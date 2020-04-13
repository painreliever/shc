package com.painreliever.shc

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.{Unmarshal, FromEntityUnmarshaller => FEU}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorAttributes, OverflowStrategy, QueueOfferResult, Supervision}
import com.painreliever.shc.HttpClient._
import com.painreliever.shc.service.{HttpLatencyMetricsReporter, NoLatencyReporter}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.{Seq => ISeq}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

class HttpClient(service: String,
                 timeout: FiniteDuration,
                 reportLatency: HttpLatencyMetricsReporter = new NoLatencyReporter,
                 logger: Logger = LoggerFactory.getLogger(classOf[HttpClient]),
                 requestBufferSize: Int = 1000000)(implicit s: ActorSystem, ec: ExecutionContext) {
  private type QueueIn = (HttpRequest, Promise[HttpResponse])
  private type QueueOut = (Try[HttpResponse], Promise[HttpResponse])
  private val flow: Flow[QueueIn, QueueOut, NotUsed] = Http().superPool[Promise[HttpResponse]]()
  private final val queue = Source
    .queue[QueueIn](requestBufferSize, OverflowStrategy.backpressure)
    .via(flow)
    .toMat(Sink.foreach { case (resp, promise) ⇒ promise.complete(resp) })(Keep.left)
    .withAttributes(ActorAttributes.supervisionStrategy(_ => Supervision.resume))
    .run()

  private def offer(request: HttpRequest, p: Promise[HttpResponse]): Future[HttpResponse] = {
    queue.offer(request -> p).transformWith {
      case Success(QueueOfferResult.Enqueued) ⇒ p.future
      case Success(r) => Future.failed(new OfferQueueException(s"got $r"))
      case Failure(_) => Future.failed(new OfferQueueException("failed to enqueue"))
    }
  }

  private def apply[Out](clientRequest: HttpClientRequest)(implicit feu: FEU[Out]): Future[Out] =
    meter(clientRequest) {
      offer(clientRequest.request, Promise[HttpResponse]()) flatMap {
        case HttpResponse(code, _, entity, _) if code.isSuccess() ⇒
          entity.withoutSizeLimit().toStrict(timeout).flatMap(Unmarshal(_).to[Out]).map(_ -> code)
        case HttpResponse(code, _, entity, _) =>
          val eventualContent = entity.withoutSizeLimit().dataBytes.map(_.utf8String).runWith(Sink.head)
          eventualContent.transform { content =>
            val cause = content.failed.toOption
            Failure(StatusCodeException(code, content.getOrElse("error reading response"), cause.orNull))
          }
      }
    }

  def post[Out](uri: HttpClientUrl, entity: RequestEntity, headers: (String, String)*)(
      implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.POST, uri, akkaHeaders(headers), entity))

  def post[Out](uri: HttpClientUrl)(implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.POST, uri, Nil, HttpEntity.Empty))

  def put[Out](uri: HttpClientUrl, entity: RequestEntity, headers: (String, String)*)(
      implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.PUT, uri, akkaHeaders(headers), entity))

  def patch[Out](uri: HttpClientUrl, entity: RequestEntity)(implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.PATCH, uri, Nil, entity))

  def patch[Out](uri: HttpClientUrl)(implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.PATCH, uri, Nil, HttpEntity.Empty))

  def get[Out](uri: HttpClientUrl, headers: (String, String)*)(implicit feu: FEU[Out]): Future[Out] =
    apply(httpRequest(HttpMethods.GET, uri, akkaHeaders(headers), HttpEntity.Empty))

  def exists(uri: HttpClientUrl, headers: (String, String)*): Future[Boolean] = {
    val clientRequest = httpRequest(HttpMethods.HEAD, uri, akkaHeaders(headers), HttpEntity.Empty)
    meter(clientRequest) {
      offer(clientRequest.request, Promise[HttpResponse]()) map { response =>
        Try(response.entity.discardBytes())
        response.status.isSuccess -> response.status
      }
    }
  }

  private def akkaHeaders(headers: Iterable[(String, String)]) = headers.map(RawHeader.apply _ tupled).toVector

  private def httpRequest(method: HttpMethod, url: HttpClientUrl, headers: ISeq[HttpHeader], entity: RequestEntity) =
    HttpClientRequest(url.toHttpRequest(method, headers, entity), url.urlTemplate)

  private def meter[T](clientRequest: HttpClientRequest)(f: Future[(T, StatusCode)]): Future[T] = {
    val startTime = System.nanoTime()
    def responseTime(): Double = (System.nanoTime() - startTime) / 1000000.0

    val request = clientRequest.request
    val url = request.uri
    val method = request.method.value
    f.transform { result =>
      val latencyMs = responseTime()
      val latencyLong = latencyMs.longValue()
      reportLatency(service, clientRequest.urlTemplate, latencyMs / 1000)
      result match {
        case Success((result, code)) ⇒
          logger.info(s"$service ok $method code:$code ${latencyLong}ms $url with ${cut(result)}")
          Success(result)
        case Failure(e @ StatusCodeException(code, content, _)) ⇒
          logger.warn(s"$service ko $method code:$code ${latencyLong}ms $url with ${cut(content)}")
          Failure(e)
        case Failure(e) =>
          logger.error(s"$service ko $method code:unknown ${latencyLong}ms $url with ${cut(e.getMessage)}")
          Failure(e)
      }
    }
  }

  private def cut[T](v: T, limit: Int = 100): String = {
    val str = v.toString
    if (str.length > limit) str.substring(0, limit) else str
  }
}

object HttpClient {

  case class StatusCodeException(code: StatusCode, response: String, cause: Throwable)
      extends Exception(code.toString, cause)
      with NoStackTrace

  case class HttpClientUrl(urlTemplate: String, fullPath: String, query: Uri.Query = Uri.Query.Empty) {
    def withParams(params: (String, String)*): HttpClientUrl =
      HttpClientUrl(urlTemplate, fullPath, Uri.Query(query.toMap ++ params))

    def toHttpRequest(method: HttpMethod, headers: ISeq[HttpHeader], entity: RequestEntity): HttpRequest =
      HttpRequest(method, toFullUri, headers, entity)

    def toFullUri: Uri = {
      val uri = Uri(fullPath)
      uri.withQuery(Uri.Query(Uri.Query(uri.rawQueryString) ++ query: _*))
    }
  }

  case class HttpClientRequest(request: HttpRequest, urlTemplate: String)

  class OfferQueueException(reason: String) extends Exception(reason) with NoStackTrace

  def file(param: String, filename: String, content: String)(implicit ec: ExecutionContext): Future[RequestEntity] = {
    val formData = Multipart.FormData.Strict(
      Vector(
        Multipart.FormData.BodyPart.Strict(
          param,
          HttpEntity(ContentTypes.`application/octet-stream`, content.getBytes("UTF-8")),
          Map("filename" -> filename)
        ))
    )
    Marshal(formData).to[RequestEntity]
  }

//  implicit class EntityOps[T](val e: T) extends AnyVal {
//    def entity(implicit wt: JsonWriter[T]): RequestEntity =
//      HttpEntity(ContentTypes.`application/json`, wt.write(e).compactPrint)
//  }

  implicit class UrlInterpolator(val sc: StringContext) extends AnyVal {
    def url(params: Any*): HttpClientUrl = {
      val template = new StringBuilder(sc.parts(0))
      val fullUrl = new StringBuilder(sc.parts(0))
      sc.parts.iterator.drop(1).zipWithIndex.foreach {
        case (part, i) =>
          template.append('$').append(i + 1).append(part)
          fullUrl.append(params(i)).append(part)
      }
      HttpClientUrl(template.toString(), fullUrl.toString())
    }
  }
}
