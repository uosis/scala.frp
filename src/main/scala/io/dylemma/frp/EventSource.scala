package io.dylemma.frp

import impl._
import java.util.concurrent.atomic.AtomicBoolean
import scala.util._

object EventSource {
	/** Convenience method for creating an `EventSource` instance, given some type, `A`. */
	def apply[A](): EventSource[A] = new EventSource[A] {}
}

/** EventSource is an implementation of [[EventStream]] that adds `fire` and `stop` methods.
  * Usage in client code will generally look something like
  * {{{
  * class CoolThings {
  * 	private val _events = EventSource[Thing]
  *
  * 	// don't expose `fire` and `stop` publicly: EventStream is read-only
  * 	def events: EventStream[Thing] = _events
  *
  * 	def doThings = { events fire new Thing(...) }
  * }
  * }}}
  */
trait EventSource[A] extends EventStream[A] with EventSourceImpl[A] {
	private val _stopped = new AtomicBoolean(false)

	private type Handler = Event[A] => Boolean

	def clear(): Unit = handlers.clear()
	def stopped: Boolean = _stopped.get
	def stop(): Unit = if (_stopped.compareAndSet(false, true)) produce(Stop)
	def fire(event: A): Unit = {
		if (stopped) throw new IllegalStateException("Cannot fire events from a stopped EventSource")
		else produce(Fire(event))
	}

	private val handlers = scala.collection.mutable.WeakHashMap.empty[Handler, Unit]

	private[frp] def addHandler(handler: Handler): Unit = {
		handlers.put(handler, ())
	}
	private[frp] def removeHandler(handler: Handler): Unit = {
		handlers.remove(handler)
	}

	/** Produce a new item. All `handler` functions will be called with `item` as
	  * the argument. There is no guarantee of the order in which the `handler`
	  * functions will be called.
	  *
	  * @param item The item to be sent to all `handler`s (sinks).
	  */
	protected def produce(item: Event[A]): Unit = {
		val handlerResults = handlers.toList.map(kv => (kv._1, Try { kv._1(item) }))

		handlerResults.collect {
			case (h, Success(false)) => removeHandler(h)
		}

		handlerResults.collect {
			case (_, Failure(ex)) => ex
		}.headOption.foreach(t => throw t)
	}
}
