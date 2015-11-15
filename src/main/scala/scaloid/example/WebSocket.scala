package scaloid.example

import com.neovisionaries.ws.client._
import org.scaloid.common._

import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}
import scala.reflect.ClassTag
import scala.util.{Success, Failure, Try}

import scala.collection.JavaConversions._

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
      case Some((promise, body)) =>
        promise.success(body)
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

class WebSocketRequest(val request: String) {
  def onSuccess(response: String): Unit = {}
  def onFailure(error: Throwable): Unit = {}
}

case object WebSocketFinalizeRequest extends WebSocketRequest("")

trait WebSocketStateListener {
  def onSocketResult(result: Throwable): Unit
}

class ShutdownableBlockingDeque[T <: AnyRef](implicit c:ClassTag[T])
  extends java.util.concurrent.LinkedBlockingDeque[T] {

  private var enabled: Boolean = true

  def shutdown(): Option[Iterator[T]] = synchronized {
    if (enabled) {
      enabled = false
      val items = toArray(Array[T]())
      clear()
      Some(items.iterator)
    } else {
      None
    }
  }

  override def offer(d: T): Boolean = synchronized {
    if (enabled) super.offer(d)
    else false
  }

  override def offerFirst(d: T): Boolean = synchronized {
    if (enabled) super.offerFirst(d)
    else false
  }
}

case object AlreadyClosedException extends RuntimeException
case object ClosingException extends RuntimeException
case class SendException(frame: String) extends RuntimeException

class WebSocketCB(parameters: WebSocketParameters, listener: scala.ref.WeakReference[WebSocketStateListener])
  extends WebSocketAdapter
  with Runnable
  with TagUtil {

  private val queue = new ShutdownableBlockingDeque[WebSocketRequest]
  private val waitings = new scala.collection.mutable.HashMap[Int, WebSocketRequest]

  new Thread(this).start()

  def request(data: WebSocketRequest): Boolean = {
    if (queue.offer(data)) {
      true
    } else {
      data.onFailure(AlreadyClosedException)
      false
    }
  }

  def close(): Boolean = queue.offerFirst(WebSocketFinalizeRequest)

  def run(): Unit = {
    val factory = new WebSocketFactory
    try {
      val socket = factory.createSocket(parameters.uri, parameters.timeout)
        .addHeader("cookie", parameters.cookie)
        .addListener(this)
        .connect()
      Iterator.continually(queue.take()).forall({
        case WebSocketFinalizeRequest =>
          queue.shutdown().get.foreach(_.onFailure(ClosingException))
          waitings.synchronized {
            val list = waitings.toList
            waitings.clear()
            list
          }.foreach(_._2.onFailure(ClosingException))
          socket.disconnect()
          false
        case data: WebSocketRequest =>
          waitings.synchronized(waitings += ((data.hashCode, data)))
          socket.sendText(s"${data.hashCode} ${data.request}")
          true
      })
    } catch {
      case e: WebSocketException => listener.get.foreach(_.onSocketResult(e))
    }
  }

  protected def parseFrame(message: String): Option[(WebSocketRequest, String)] = {
    try {
      val responses = message.split(" ", 2)
      if (responses.length < 2) {
        None
      } else {
        val hashCode = java.lang.Integer.parseInt(responses(0))
        waitings.get(hashCode) match {
          case Some(data) =>
            waitings.synchronized(waitings -= hashCode)
            Some(data, responses{1})
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
      case Some((data, response)) =>
        data.onSuccess(response)
      case None =>
        error(s"unknown message $message")
    }
  }

  override def onDisconnected(s: WebSocket, sf: WebSocketFrame, cf: WebSocketFrame, closedByServer: Boolean): Unit =
    if (closedByServer) listener.get.foreach(_.onSocketResult(ClosingException))

  override def onSendError(s: WebSocket, cause: WebSocketException, frame: WebSocketFrame): Unit =
    listener.get.foreach(_.onSocketResult(SendException(if (frame != null) frame.getPayloadText else "")))

}