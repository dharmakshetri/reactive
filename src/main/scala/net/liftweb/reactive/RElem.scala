package net.liftweb.reactive


import net.liftweb.http._
	import js._
		import JsCmds._
		import JE._
import net.liftweb.util.Helpers._
import net.liftweb.common._
import net.liftweb.actor._
import scala.xml._

import reactive._

trait Renderable {
  def render: NodeSeq
}
object Renderable {
  implicit def toNodeSeq(r: Renderable) = r.render
}

object Span {
  def apply(
    content: EventStream[NodeSeq] = new EventStream[NodeSeq] {}
  )(implicit page: Page) = {
    val contentES = content
    new Span {
      for(change <- contentES) content ()= change
    }
  }
}
class Span(
	contentES: EventStream[NodeSeq] = new EventStream[NodeSeq] {}
)(
	implicit val page: Page
) extends Renderable {
	val content: Var[NodeSeq] = Var(NodeSeq.Empty)
	lazy val id = randomString(20)
	for(change <- contentES) {
	  content ()= change
	}
	page.observe(content) { content =>
	  Reactions.inAnyScope(page) {
	    Reactions.queue(SetHtml(id, content))
	  }
		true
	}
	def render = <span id={id}>{content.now}</span>
	
}

object RElem {
  private[reactive] val elems = new scala.collection.mutable.WeakHashMap[String,RElem]
  def ajaxFunc(f: String=>Unit): List[String]=>JsCmd = {
    case Nil => JsCmds.Noop
    case s :: _ => Reactions.inClientScope(f(s))
  }
}
trait RElem {
  lazy val id = randomString(20)
  
  def events: Seq[JSEventSource[_<:JSEvent]]
  def properties: Seq[JSProperty]
  
  def baseElem: Elem
  
  def render: Elem = {
    val e = baseElem % new UnprefixedAttribute("id", id, Null)
    val withProps = properties.foldLeft(e){
      case (e, prop) => e % prop.asAttribute
    }
    events.foldLeft[Elem](withProps){
      case (e, evt: JSEventSource[_]) => e % evt.asAttribute
      case (e, _) => e
    }
  }
}

/**
 * Represents a javascript property synchronized in from the client to the server
*/
trait JSProperty {
  val value: Var[String] = new Var("")
  def name: String
  def elemId: String
  def eventDataKey = "jsprop" + name
  implicit def page: Page
  
  private val isRespondingToAjax = new scala.util.DynamicVariable(false)
  
  def readJS: JsExp = JsRaw("document.getElementById('" + elemId + "')." + name)
  def writeJS(value: String): JsCmd = SetExp(readJS, value)
  
  def updateOn(es: JSEventSource[_]) {
    es.extraEventData += (eventDataKey -> readJS)
    es.extraEventStream foreach {evt =>
      println(evt.get(eventDataKey))
      evt.get(eventDataKey) foreach {v =>
        isRespondingToAjax.withValue(true){
          value.update(v)
        }
      }
    }
    
  }
  
  def asAttribute = new UnprefixedAttribute(name, value.now, Null)
  
  value foreach {v =>
    if(!isRespondingToAjax.value) {
      Reactions.inAnyScope(page) {
        Reactions.queue(writeJS(v))
      }
    }
  }
}

/**
 * Represents a javascript event type propagated to the server. 
 * Generates the javascript necessary for an event listener to
 * pass the event to the server.
 */
class JSEventSource[T <: JSEvent : Manifest] {
  val eventStream = new EventStream[T] with TracksAlive[T] {}
  def eventName = JSEvent.eventName[T]
  def attributeName = "on" + eventName
  
  var extraEventData = Map[String, JsExp]()
  val extraEventStream =
    new EventStream[Map[String, String]]
    with TracksAlive[Map[String, String]] {}
  
  def propagateJS: String = {
    def encodeEvent = {
      def modifiers =
        "'altKey='+event.altKey+';ctrlKey='+event.ctrlKey+';metaKey='+event.metaKey+';shiftKey='+event.shiftKey"
  		def buttons = "'button='+event.button+';'+" + modifiers
  		def position = "'clientX='+event.clientX+';clientY='+event.clientY"
  		def mouse = position + "+';'+" + buttons
  		def key = "'code='+(event.keyCode||event.charCode)+';'+" + modifiers
  		def relatedTarget = "'related='+encodeURIComponent(event.relatedTarget.id)"
  		def fromElement = "'related='+encodeURIComponent(event.fromElement.id)"
  		def toElement = "'related='+encodeURIComponent(event.toElement.id)"
  		def out = mouse + "+';'+" + (
		    if(S.request.dmap(false)(_.isIE)) toElement else relatedTarget
  		)
  		def over = mouse + "+';'+" + (
		    if(S.request.dmap(false)(_.isIE)) fromElement else relatedTarget
  		)
  		val eventEncoding = if(!eventStream.alive.now) {
		    ""
  		} else eventName match {
  		  case "blur" | "change" | "error" | "focus" | "resize" | "unload" => ""
  		  case "click" | "dblclick" | "select" => modifiers
  		  case "keydown" | "keypress" | "keyup" => key
  		  case "mousedown" | "mousemove" | "mouseup" => mouse
  		  case "mouseout" => out
  		  case "mouseover" => over
  		}
      if(!extraEventStream.alive.now)
        eventEncoding
      else extraEventData.foldLeft(eventEncoding){
        case (encoding, (key, expr)) =>
          //  xxx + ';key=' + encodeURIComponent(expr)
          encoding + "+';" + key + "='+encodeURIComponent(" + expr.toJsCmd + ")"
      }
    }
    
    def decodeEvent(evt: Map[String,String]): T = {
      def bool(s: String) = evt(s) match { case "false"|"undefined" => false; case "true" => true }
      def modifiers = Modifiers(bool("altKey"), bool("ctrlKey"), bool("shiftKey"), bool("metaKey"))
      def buttons = {
        val b = evt("buttons").toInt
        if(S.request.dmap(false)(_.isIE))
          Buttons((b&1)!=0,(b&4)!=0,(b&2)!=0, modifiers)
        else
          Buttons(b==0,b==1,b==2, modifiers)
      }
      def position = Position((evt("clientX").toInt, evt("clientY").toInt))
      
      (eventName match {
        case "blur" => Blur
        case "change" => Change
        case "click" => Click(modifiers)
        case "dblclick" => DblClick(modifiers)
        case "error" => Error
        case "focus" => Focus
        case "keydown" => KeyDown(evt("code").toInt, modifiers)
        case "keyup" => KeyUp(evt("code").toInt, modifiers)
        case "keypress" => KeyPress(evt("code").toInt, modifiers)
        case "mousedown" => MouseDown(buttons, position)
        case "mousemove" => MouseMove(buttons, position)
        case "mouseup" => MouseUp(buttons, position)
        case "mouseover" => MouseOver(buttons, position, RElem.elems.get(evt("related")))
        case "mouseout" => MouseOut(buttons, position, RElem.elems.get(evt("related")))
        case "resize" => Resize
        case "select" => Select(modifiers)
        case "unload" => Unload
      }).asInstanceOf[T]
    }
    def handler(s: String) = {
      val evt: Map[String,String] = Map(
        s.split(";").toList.flatMap {
          _.split("=").toList match {
            case property :: value :: Nil => Some((property, urlDecode(value)))
            case property :: Nil => Some((property, ""))
            case _ => None
          }
        }: _*
      )
      eventStream.fire(decodeEvent(evt))
      extraEventStream.fire(evt)
    }
    S.fmapFunc(S.contextFuncBuilder(RElem.ajaxFunc(handler))) {funcId =>
      SHtml.makeAjaxCall(
        JsRaw("'"+funcId+"=' + encodeURIComponent("+encodeEvent+")")
      ).toJsCmd
    }
  }
  def asAttribute: xml.MetaData = if(eventStream.alive.now || extraEventStream.alive.now) {
    new xml.UnprefixedAttribute(
      attributeName,
      propagateJS,
      xml.Null
    )
  } else {
    xml.Null
  }
}

/**
 * Represents an x(ht)ml attribute on the client updatable from the server
*/
trait JSAttribute {
  
}
/**
*/
trait JSContent {
  
}
class TextField(
	valueES: EventStream[String] = new EventStream[String] {}
)(implicit val page: Page) extends RElem { elem =>
  
  val dblClick = new JSEventSource[DblClick]
  val keyUp = new JSEventSource[KeyUp]
  val value = new JSProperty {
    def name = "value"
    def elemId = elem.id
    implicit def page = TextField.this.page
    this updateOn keyUp
  }
		  
	def events = List(dblClick, keyUp)
	def properties = List(value)
	def baseElem = <input type="text" />

  valueES foreach value.value.update
}
