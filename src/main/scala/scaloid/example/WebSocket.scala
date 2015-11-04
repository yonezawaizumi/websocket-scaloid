package scaloid.example

import com.neovisionaries.ws.client._
import org.json.{JSONException, JSONObject}
import org.scaloid.common._

import scala.concurrent.{Promise, Future}
import scala.util.{Success, Failure, Try}

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}

object ExecutionContext {
  val exec = scala.concurrent.ExecutionContext.fromExecutor(
    new ThreadPoolExecutor(100, 100, 1000, TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable]))
}

case class WebSocketParameters(uri: String, timeout: Int, cookie: String)

private case class Request(sequence: Long, request: String, promise: Promise[String])

case object WebSocketClosed

sealed class WebSocketFailure(reason: Int) extends RuntimeException
case object WebSocketNotConnectedFailure extends WebSocketFailure(R.string.web_socket_error_not_connected)
case object WebSocketConnectionFailure extends WebSocketFailure(R.string.web_socket_error_connection)
case object WebSocketDisconnectedFailure extends WebSocketFailure(R.string.web_socket_error_disconnected)
case object WebSocketSendFailure extends WebSocketFailure(R.string.web_socket_error_send)
case object WebSocketServerFailure extends WebSocketFailure(R.string.web_socket_error_server)
case object WebSocketAuthFailure extends WebSocketFailure(R.string.web_socket_error_auth)
case object WebSocketUnknownFailure extends WebSocketFailure(R.string.web_socket_error_unknown)

/*object WebSOcketFailure {
  def apply(e: WebSocketException) = {
    match e.getError {
      case WebSocketError.
    }
  }
}*/

class WebSocketPP(parameters: WebSocketParameters)
  extends WebSocketAdapter with TagUtil {

  val sequenceKey = "sequence"
  val bodyKey = "body"
  implicit val exec = ExecutionContext.exec

  private val sequencer = new java.util.concurrent.atomic.AtomicLong
  private val promises = new scala.collection.mutable.LongMap[Promise[String]]

  val socketState = Promise[AnyRef]
  private val socket: Future[WebSocket] = Future {
    info("socket creation")
    val factory = new WebSocketFactory
    factory.createSocket(parameters.uri, parameters.timeout)
      .addHeader("cookie", parameters.cookie)
      .addListener(this)
      .connect()
  }

  def request(requestString: String): Future[String] = {
    val promise = Promise[String]
    socket.map { socket =>
      info(s"request ${requestString}")
      val sequence = sequencer.incrementAndGet
      val json = new JSONObject
      json.put(sequenceKey, sequence)
      json.put(bodyKey, requestString)
      promises.synchronized { promises += (sequence, promise) }
      try {
        socket.sendText(json.toString)
      } catch {
        case e: WebSocketException =>
          promises.synchronized { promises -= sequence }
          promise.failure(e)
      }
    }
    promise.future
  }

  def close: Unit = socket.foreach(_.disconnect)

  protected def parseFrame(message: String): Option[(Promise[String], String)] = {
    Try {
      val json = new JSONObject(message)
      val sequence = json.getLong(sequenceKey)
      val body = json.getString(bodyKey)
      val promise = promises.get(sequence)
      promise match {
        case Some(promise) =>
          promises.synchronized { promises -= sequence }
          Some(promise, body)
        case None =>
          None
      }
    }.recover {
      case e: JSONException => None
    }.get
  }

  override def onTextMessage(s: WebSocket, message: String): Unit = {
    info(s"response ${message}")
    parseFrame(message) match {
      case Some(tuple) =>
        tuple._1.success(tuple._2)
      case None =>
        //何らかのログ吐出
    }
  }

  override def onDisconnected(s: WebSocket, sf: WebSocketFrame, cf: WebSocketFrame, closedByServer: Boolean): Unit = {
    promises.synchronized {
      val promises_ = promises.toList
      promises.clear
      promises_
    }.foreach(_._2.failure(WebSocketDisconnectedFailure))

    if (closedByServer) {
      socketState.failure(WebSocketDisconnectedFailure)
    } else {
      socketState.success(WebSocketClosed)
    }
  }

  override def onSendError(s: WebSocket, cause: WebSocketException, frame: WebSocketFrame): Unit =
    parseFrame(frame.getPayloadText).foreach(_._1.failure(WebSocketSendFailure))

}
