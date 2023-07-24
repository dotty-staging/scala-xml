/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package xml
package parsing

import scala.collection.Seq
import org.xml.sax.Attributes
import org.xml.sax.ext.DefaultHandler2

// can be mixed into FactoryAdapter if desired
trait ConsoleErrorHandler extends DefaultHandler2 {
  // ignore warning, crimson warns even for entity resolution!
  override def warning(ex: SAXParseException): Unit = {}
  override def error(ex: SAXParseException): Unit = printError("Error", ex)
  override def fatalError(ex: SAXParseException): Unit = printError("Fatal Error", ex)

  protected def printError(errtype: String, ex: SAXParseException): Unit =
    Console.withOut(Console.err) {
      val s = "[%s]:%d:%d: %s".format(
        errtype, ex.getLineNumber, ex.getColumnNumber, ex.getMessage)
      Console.println(s)
      Console.flush()
    }
}

/**
 * SAX adapter class, for use with Java SAX parser. Keeps track of
 *  namespace bindings, without relying on namespace handling of the
 *  underlying SAX parser.
 */
abstract class FactoryAdapter extends DefaultHandler2 with factory.XMLLoader[Node] {
  var prolog: List[Node|Null] = List.empty
  var rootElem: Node = _
  var epilogue: List[Node|Null] = List.empty

  val buffer: StringBuilder = new StringBuilder()
  private var inCDATA: Boolean = false

  /** List of attributes
    * 
    * Previously was a mutable [[scala.collection.mutable.Stack Stack]], but is now a mutable reference to an immutable [[scala.collection.immutable.List List]].
    * 
    * @since 2.0.0 
    */
  var attribStack: List[MetaData] = List.empty
  /** List of elements
    * 
    * Previously was a mutable [[scala.collection.mutable.Stack Stack]], but is now a mutable reference to an immutable [[scala.collection.immutable.List List]].
    * 
    * @since 2.0.0 
    */
  var hStack: List[Node|Null] = List.empty // [ element ] contains siblings
  /** List of element names
    * 
    * Previously was a mutable [[scala.collection.mutable.Stack Stack]], but is now a mutable reference to an immutable [[scala.collection.immutable.List List]].
    * 
    * @since 2.0.0 
    */
  var tagStack: List[String] = List.empty
  /** List of namespaces
    * 
    * Previously was a mutable [[scala.collection.mutable.Stack Stack]], but is now a mutable reference to an immutable [[scala.collection.immutable.List List]].
    * 
    * @since 2.0.0 
    */
  var scopeStack: List[NamespaceBinding] = List.empty

  var curTag: String = _
  var capture: Boolean = false

  // abstract methods

  /**
   * Tests if an XML element contains text.
   * @return true if element named `localName` contains text.
   */
  def nodeContainsText(localName: String): Boolean // abstract

  /**
   * creates an new non-text(tree) node.
   * @param elemName
   * @param attribs
   * @param chIter
   * @return a new XML element.
   */
  def createNode(pre: String|Null, elemName: String, attribs: MetaData,
                 scope: NamespaceBinding, chIter: List[Node]): Node // abstract

  /**
   * creates a Text node.
   * @param text
   * @return a new Text node.
   */
  def createText(text: String): Text // abstract

  /**
   * creates a PCData node.
   * @param text
   * @return a new PCData node.
   */
  def createPCData(text: String): PCData // abstract

  /**
   * creates a new processing instruction node.
   */
  def createProcInstr(target: String, data: String): Seq[ProcInstr]

  /**
   * creates a new comment node.
   */
  def createComment(characters: String): Seq[Comment]

  //
  // ContentHandler methods
  //

  val normalizeWhitespace: Boolean = false

  /**
   * Capture characters, possibly normalizing whitespace.
   * @param ch
   * @param offset
   * @param length
   */
  override def characters(ch: Array[Char], offset: Int, length: Int): Unit = {
    if (!capture) ()
    // compliant: report every character
    else if (!normalizeWhitespace) buffer.appendAll(ch, offset, length)
    // normalizing whitespace is not compliant, but useful
    else {
      var it = ch.slice(offset, offset + length).iterator
      while (it.hasNext) {
        val c = it.next()
        val isSpace = c.isWhitespace
        buffer append (if (isSpace) ' ' else c)
        if (isSpace)
          it = it dropWhile (_.isWhitespace)
      }
    }
  }

  /**
   * Start of a CDATA section.
   */
  override def startCDATA(): Unit = {
    captureText()
    inCDATA = true
  }

  /**
   * End of a CDATA section.
   */
  override def endCDATA(): Unit = captureText()

  private def splitName(s: String): (String|Null, String) = {
    val idx = s indexOf ':'
    if (idx < 0) (null, s)
    else (s take idx, s drop (idx + 1))
  }

  /* ContentHandler methods */

  /* Start element. */
  override def startElement(
    uri: String,
    _localName: String,
    qname: String,
    attributes: Attributes): Unit =
    {
      captureText()

      // capture the prolog at the start of the root element
      if (tagStack.isEmpty) {
        prolog = hStack.reverse
        hStack = List.empty
      }

      tagStack = curTag :: tagStack
      curTag = qname

      val localName = splitName(qname)._2
      capture = nodeContainsText(localName)

      hStack = null :: hStack
      var m: MetaData = Null
      var scpe: NamespaceBinding =
        if (scopeStack.isEmpty) TopScope
        else scopeStack.head

      for (i <- (0 until attributes.getLength).reverse) {
        val qname = attributes getQName i
        val value = attributes getValue i
        val (pre, key) = splitName(qname)
        def nullIfEmpty(s: String): String|Null = if (s == "") null else s

        if (pre == "xmlns" || (pre == null && qname == "xmlns")) {
          val arg = if (pre == null) null else key
          scpe = NamespaceBinding(arg, nullIfEmpty(value), scpe)
        } else
          m = Attribute(Option.fromNullable(pre), key, Text(value), m)
      }

      // Add namespace bindings for the prefix mappings declared by this element
      // (if there are any, the parser is namespace-aware, and no namespace bindings were delivered as attributes).
      // All `startPrefixMapping()` events will occur immediately before the corresponding `startElement()` event.
      for ((prefix: String, uri: String) <- prefixMappings)
        scpe = NamespaceBinding(if (prefix.isEmpty) null else prefix, uri, scpe)

      // Once the `prefixMappings` are processed into `scpe`, the list is emptied out
      // so that already-declared namespaces are not re-declared on the nested elements.
      prefixMappings = List.empty

      scopeStack = scpe :: scopeStack
      attribStack =  m :: attribStack
    }

  private var prefixMappings: List[(String, String)] = List.empty

  override def startPrefixMapping(prefix: String, uri: String): Unit =
    prefixMappings = (prefix, uri) :: prefixMappings

  /**
   * Captures text or cdata.
   */
  def captureText(): Unit = {
    if (capture && buffer.nonEmpty) {
      val text: String = buffer.toString
      val newNode: Node = if (inCDATA) createPCData(text) else createText(text)
      hStack =  newNode :: hStack
    }

    buffer.clear()
    inCDATA = false
  }

  /**
   * End element.
   * @param uri
   * @param _localName
   * @param qname
   * @throws org.xml.sax.SAXException if ..
   */
  override def endElement(uri: String, _localName: String, qname: String): Unit = {
    captureText()
    val metaData = attribStack.head
    attribStack = attribStack.tail

    // reverse order to get it right
    val v = hStack.takeWhile(_ != null).reverse.asInstanceOf[List[Node]]
    hStack = hStack.dropWhile(_ != null) match {
      case null :: hs => hs
      case hs => hs
    }
    val (pre, localName) = splitName(qname)
    val scp = scopeStack.head
    scopeStack = scopeStack.tail

    // create element
    rootElem = createNode(pre, localName, metaData, scp, v)
    hStack = rootElem :: hStack
    curTag = tagStack.head
    tagStack = tagStack.tail
    capture = (curTag:Any) != null && nodeContainsText(curTag) // root level
  }

  override def endDocument(): Unit =  {
    // capture the epilogue at the end of the document
    epilogue = hStack.init.reverse
    hStack = hStack.last :: Nil
  }

  /**
   * Processing instruction.
   */
  override def processingInstruction(target: String, data: String): Unit = {
    captureText()
    hStack = hStack.reverse_:::(createProcInstr(target, data).toList)
  }

  /**
   * Comment.
   */
  override def comment(ch: Array[Char], start: Int, length: Int): Unit = {
    captureText()
    val commentText: String = String.valueOf(ch.slice(start, start + length))
    hStack = hStack.reverse_:::(createComment(commentText).toList)
  }
}
