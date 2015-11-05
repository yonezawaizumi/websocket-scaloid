package scaloid.example

import com.neovisionaries.ws.client._
import org.scaloid.common._

import scala.concurrent.{Promise, Future}
import scala.util.Try

case class WebSocketParameters(uri: String, timeout: Int, cookie: String)

private case class Request(sequence: Long, request: String, promise: Promise[String])

class WebSocketPF(parameters: WebSocketParameters)
  extends WebSocketAdapter with TagUtil {

  implicit val exec = ExecutionContext.exec

  private val sequencer = new java.util.concurrent.atomic.AtomicLong
  private val promises = new scala.collection.mutable.LongMap[ Promise[String]]

  private val socket: Future[WebSocket] = Future {
    val factory = new WebSocketFactory
    factory.createSocket(parameters.uri, parameters.timeout)
      .addHeader("cookie", parameters.cookie)
      .addListener(this)
      .connect()
  }

  def request(requestString: String): Future[String] = {
    val promise = Promise[String]()
    val sequence = sequencer.incrementAndGet
    val request = s"${sequence.toString} $requestString"
    promises.synchronized(promises += (sequence, promise))
    socket.map { socket =>
      if (socket.isOpen) Future { socket.sendText(request) }
      else throw new IllegalStateException
    }.onFailure {
      case t =>
        info(s"socket failed $t")
        promises.synchronized(promises -= sequence)
        promise.failure(t)
    }
    promise.future
  }

  private def isOpen(t: Try[WebSocket]): Boolean = t.map(s => s.isOpen).getOrElse(false)
  def isReady: Boolean = socket.value.fold(true)(isOpen)
  def isOpen: Boolean = socket.value.fold(false)(isOpen)

  def close(): Unit = socket.foreach(_.disconnect)

  protected def parseFrame(message: String): Option[(Promise[String], String)] = {
    try {
      val responses = message.split(" ", 2)
      if (responses.length < 2) {
        None
      } else {
        val sequence = java.lang.Long.parseLong(responses(0))
        val body = responses(1)
        val promise = promises.get(sequence)
        promise match {
          case Some(promise_) =>
            promises.synchronized(promises -= sequence)
            Some(promise_, body)
          case None =>
            None
        }
      }
    } catch {
      case e: Throwable => None
    }
  }

  override def onTextMessage(s: WebSocket, message: String): Unit = {
    parseFrame(message) match {
      case Some(tuple) =>
        tuple._1.success(tuple._2)
      case None =>
        error(s"unknown message $message")
    }
  }

  override def onDisconnected(s: WebSocket, sf: WebSocketFrame, cf: WebSocketFrame, closedByServer: Boolean): Unit = {
    promises.synchronized {
      val promises_ = promises.toList
      promises.clear()
      promises_
    }.foreach(_._2.failure(new IllegalStateException))
  }

  override def onSendError(s: WebSocket, cause: WebSocketException, frame: WebSocketFrame): Unit =
    parseFrame(frame.getPayloadText).foreach(_._1.failure(cause))

  override def onError(s: WebSocket, cause: WebSocketException): Unit = {
    error(cause.getMessage)
  }

}
