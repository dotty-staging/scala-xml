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

import scala.collection.Seq

/**
 * Unprefixed attributes have the null namespace, and no prefix field
 *
 *  @author Burak Emir
 */
// Note: used by the Scala compiler.
class UnprefixedAttribute(
  override val key: String,
  _value: Seq[Node],
  next1: MetaData
)
  extends Attribute
{
  override val value: ScalaVersionSpecific.SeqOfNode = if (_value == null) null else _value match {
    case ns: ScalaVersionSpecific.SeqOfNode => ns
    case _ => _value.toVector
  }

  final override val pre: scala.Null = null
  override val next: MetaData = if (value != null) next1 else next1.remove(key)

  /** same as this(key, Text(value), next), or no attribute if value is null */
  def this(key: String, value: String, next: MetaData) =
    this(key, if (value != null) Text(value) else null: NodeSeq, next)

  /** same as this(key, value.get, next), or no attribute if value is None */
  def this(key: String, value: Option[Seq[Node]], next: MetaData) =
    this(key, value.orNull, next)

  /** returns a copy of this unprefixed attribute with the given next field*/
  override def copy(next: MetaData): UnprefixedAttribute = new UnprefixedAttribute(key, value, next)

  final override def getNamespace(owner: Node): String = null

  /**
   * Gets value of unqualified (unprefixed) attribute with given key, null if not found
   *
   * @param  key
   * @return value as Seq[Node] if key is found, null otherwise
   */
  override def apply(key: String): ScalaVersionSpecific.SeqOfNode =
    if (key == this.key) value else next(key)

  /**
   * Forwards the call to next (because caller looks for prefixed attribute).
   *
   * @param  namespace
   * @param  scope
   * @param  key
   * @return ..
   */
  override def apply(namespace: String, scope: NamespaceBinding, key: String): ScalaVersionSpecific.SeqOfNode =
    next(namespace, scope, key)
}
object UnprefixedAttribute {
  def unapply(x: UnprefixedAttribute): Some[(String, Seq[Node], MetaData)] = Some((x.key, x.value, x.next))
}
