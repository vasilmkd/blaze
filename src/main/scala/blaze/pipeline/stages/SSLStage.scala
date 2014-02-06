package blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngine

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

import blaze.pipeline.MidStage
import blaze.pipeline.Command.EOF
import blaze.util.Execution._
import com.typesafe.scalalogging.slf4j.Logging
import blaze.util.{BufferTools, ScratchBuffer}


/**
 * @author Bryce Anderson
 *         Created on 1/11/14
 */
class SSLStage(engine: SSLEngine, maxSubmission: Int = -1) extends MidStage[ByteBuffer, ByteBuffer] {

  import blaze.util.BufferTools._

  def name: String = s"SSLStage"

  private val maxNetSize = engine.getSession.getPacketBufferSize

  private val maxBuffer = math.max(maxNetSize, engine.getSession.getApplicationBufferSize)
  
  private var readLeftover: ByteBuffer = null

  def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]

    channelRead(size).onComplete{
      case Success(b) => readRequestLoop(b, size, new ListBuffer[ByteBuffer], p)
      case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
    }(directec)
    p.future
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readRequestLoop(buffer: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer], p: Promise[ByteBuffer]): Unit = {

    // Consolidate buffers if they exist
    val b = concatBuffers(readLeftover, buffer)
    readLeftover = null

    var bytesRead = 0
    val o = ScratchBuffer.getScratchBuffer(maxBuffer)

    while(true) {

      val r = engine.unwrap(b, o)

      if (r.bytesProduced() > 0) {
        bytesRead += r.bytesProduced()
        o.flip()
        out += copyBuffer(o)
        o.clear()
      }

      logger.debug(s"SSL Read Request Status: $r, $o")

      r.getHandshakeStatus match {
        case HandshakeStatus.NEED_UNWRAP =>  // must need more data
          if (r.getStatus == Status.BUFFER_UNDERFLOW) {
            readLeftover = b

            channelRead().onComplete {
              case Success(b) => readRequestLoop(b, if (size > 0) size - bytesRead else size, out, p)
              case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
            }(trampoline)
            return
          }

        case HandshakeStatus.NEED_TASK =>
          runTasks()

        case HandshakeStatus.NEED_WRAP =>
          assert(engine.wrap(BufferTools.emptyBuffer, o).bytesProduced() > 0)
          o.flip()

          channelWrite(copyBuffer(o)).onComplete {
            case Success(_) => readRequestLoop(b, if (size > 0) size - bytesRead else size, out, p)
            case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
          }(trampoline)

          return

        case _ => // NOOP
      }

      r.getStatus() match {
        case Status.OK => // NOOP

        case Status.BUFFER_OVERFLOW =>  // resize and go again
          sys.error("Shouldn't have gotten here")

        case Status.BUFFER_UNDERFLOW => // Need more data
          readLeftover = b

          if ((r.getHandshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
               r.getHandshakeStatus == HandshakeStatus.FINISHED) && !out.isEmpty) {          // We got some data so send it
            p.success(joinBuffers(out))
            return
          }
          else {
            val readsize = if (size > 0) size - bytesRead else size
            channelRead(math.max(readsize, maxNetSize)).onComplete {
              case Success(b) => readRequestLoop(b, readsize, out, p)
              case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
            }(trampoline)
            return
          }

          // It is up to the next stage to call shutdown, if that is what they want
        case Status.CLOSED =>
          if (!out.isEmpty) p.success(joinBuffers(out))
          else p.failure(EOF)
          return
      }
    }

    sys.error("Shouldn't get here")
  }

  override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = {
    val p = Promise[Any]
    writeLoop(0, data.toArray, new ListBuffer, p)
    p.future
  }

  def writeRequest(data: ByteBuffer): Future[Any] = {
    val arr = new Array[ByteBuffer](1)
    arr(0) = data
    val p = Promise[Any]
    writeLoop(0, arr, new ListBuffer, p)
    p.future
  }

  private def writeLoop(written: Int, buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer], p: Promise[Any]) {

    val o = ScratchBuffer.getScratchBuffer(maxBuffer)
    var wr = written

    while (true) {
      val r = engine.wrap(buffers, o)

      logger.debug(s"Write request result: $r, $o")

      r.getHandshakeStatus match {
        case HandshakeStatus.NEED_TASK =>
          runTasks()

        case HandshakeStatus.NEED_UNWRAP =>
          if (o.position() > 0) {
            o.flip()
            wr += o.remaining()
            out += copyBuffer(o)
            o.clear()
          }

          channelRead().onComplete {
            case Success(b) =>
              engine.unwrap(b, BufferTools.emptyBuffer)

              // TODO: this will almost certainly corrupt the state if we have some stream data...
              //storeRead(b)
              assert(!b.hasRemaining)

              writeLoop(wr, buffers, out, p)

            case f@Failure(t) => p.tryComplete(f)
          }(trampoline)
          return

        case _ => // NOOP   FINISHED, NEED_WRAP, NOT_HANDSHAKING
      }

      r.getStatus match {
        case Status.OK =>   // Successful encode
          if (o.position() > 0) {
            o.flip()
            wr += o.remaining()
            out += copyBuffer(o)
            o.clear()

            // See if we should write
            if (maxSubmission > 0 && wr > maxSubmission) {
              // Need to write
              channelWrite(out).onComplete{
                case Success(_)    => writeLoop(0, buffers, new ListBuffer, p)
                case f@ Failure(_) => p.tryComplete(f)
              }(directec)
              return
            }
          }

        case Status.CLOSED =>
          if (!out.isEmpty) {
            p.completeWith(channelWrite(out))
            return
          }
          else {
            p.tryFailure(EOF)
            return
          }

        case Status.BUFFER_OVERFLOW => // Should always have a large enough buffer
          sys.error("Shouldn't get here")

        case Status.BUFFER_UNDERFLOW => // Need more data. might never get here
          if (o.position() > 0) {
            o.flip()
            out += copyBuffer(o)
          }
          p.completeWith(channelWrite(out))
          return
      }

      if (!buffers(buffers.length - 1).hasRemaining) {
        if (o != null && o.position() > 0) {
          o.flip()
          out += copyBuffer(o)
        }

        p.completeWith(channelWrite(out))
        return
      }
    }

    sys.error("Shouldn't get here.")
  }

  override protected def stageShutdown(): Unit = {
    ScratchBuffer.clearBuffer()
    super.stageShutdown()
  }

  private def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)

  private def copyBuffer(b: ByteBuffer): ByteBuffer = {
    val bb = allocate(b.remaining())
    bb.put(b).flip()
    bb
  }

  private def joinBuffers(buffers: Seq[ByteBuffer]): ByteBuffer = {
    val sz = buffers.foldLeft(0)((sz, o) => sz + o.remaining())
    val b = allocate(sz)
    buffers.foreach(b.put(_))

    b.flip()
    b
  }

  private def runTasks() {
    var t = engine.getDelegatedTask
    while(t != null) {
      t.run()
      t = engine.getDelegatedTask
    }
  }
}


