/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable, DoubleLinkedList}
import scala.math

// Variables for dealing with sequences

/** Revert equals/hashCode behavior of Seq[A] to the default Object.
    WARNING: This doesn't actually satisfy == commutativity with a Seq[A]. :-( */
trait SeqEqualsEq[+A] extends scala.collection.Seq[A] {
  override def equals(that:Any): Boolean = that match {
    case that:AnyRef => this eq that
    case _ => false
  }
  override def hashCode: Int = java.lang.System.identityHashCode(this)
}

trait IndexedSeqEqualsEq[+A] extends SeqEqualsEq[A] with IndexedSeq[A]

trait ElementType[+ET] {
  type ElementType = ET
}

trait VarAndElementType[+This<:Variable,+ET] extends VarAndValueType[This,Seq[ET]] with ElementType[ET]

/** A variable containing a mutable sequence of other variables.  
    This variable stores the sequence itself, and tracks changes to the contents and order of the sequence. 
    @author Andrew McCallum */
trait SeqVar[X] /*extends Variable with VarAndValueType[SeqVar[X],Seq[X]] {*/ extends Variable with VarAndElementType[SeqVar[X],X] with SeqEqualsEq[X] {
  type ElementType <: Any
  type Element = VariableType#ElementType
  protected def _seq: ArrayBuffer[Element] // TODO Consider using an Array[] instead so that SeqVar[Int] is efficient.
  def value = _seq.toSeq
  def update(index:Int, x:Element)(implicit d:DiffList): Unit = UpdateDiff(index, x)
  def append(x:Element)(implicit d:DiffList) = AppendDiff(x)
  def prepend(x:Element)(implicit d:DiffList) = PrependDiff(x)
  def trimStart(n:Int)(implicit d:DiffList) = TrimStartDiff(n)
  def trimEnd(n: Int)(implicit d:DiffList) = TrimEndDiff(n)
  def remove(n:Int)(implicit d:DiffList) = Remove1Diff(n)
  def swap(i:Int,j:Int)(implicit d:DiffList) = Swap1Diff(i,j)
  def swapLength(pivot:Int,length:Int)(implicit d:DiffList) = for (i <- pivot-length until pivot) Swap1Diff(i,i+length)
  abstract class SeqVariableDiff(implicit d:DiffList) extends AutoDiff {override def variable = SeqVar.this}
  case class UpdateDiff(i:Int, x:Element)(implicit d:DiffList) extends SeqVariableDiff {val xo = _seq(i); def undo = _seq(i) = xo; def redo = _seq(i) = x}
  case class AppendDiff(x:Element)(implicit d:DiffList) extends SeqVariableDiff {def undo = _seq.trimEnd(1); def redo = _seq.append(x)}
  case class PrependDiff(x:Element)(implicit d:DiffList) extends SeqVariableDiff {def undo = _seq.trimStart(1); def redo = _seq.prepend(x)}
  case class TrimStartDiff(n:Int)(implicit d:DiffList) extends SeqVariableDiff {val s = _seq.take(n); def undo = _seq prependAll (s); def redo = _seq.trimStart(n)}
  case class TrimEndDiff(n:Int)(implicit d:DiffList) extends SeqVariableDiff {val s = _seq.drop(_seq.length - n); def undo = _seq appendAll (s); def redo = _seq.trimEnd(n)}
  case class Remove1Diff(n:Int)(implicit d:DiffList) extends SeqVariableDiff {val e = _seq(n); def undo = _seq.insert(n,e); def redo = _seq.remove(n)}
  case class Swap1Diff(i:Int,j:Int)(implicit d:DiffList) extends SeqVariableDiff { def undo = {val e = _seq(i); _seq(i) = _seq(j); _seq(j) = e}; def redo = undo }
  // for Seq trait
  def length = _seq.length
  def iterator = _seq.iterator
  def apply(index: Int) = _seq(index)
  // for changes without Diff tracking
  def +=(x:Element) = _seq += x
  def ++=(xs:Iterable[Element]) = _seq ++= xs
  def update(index:Int, x:Element): Unit = _seq(index) = x
}

abstract class SeqVariable[X](sequence: Seq[X]) extends SeqVar[X] with VarAndElementType[SeqVariable[X],X] with VarAndValueGenericDomain[SeqVariable[X],Seq[X]] {
  def this() = this(Nil)
  protected val _seq: ArrayBuffer[X] = { val a = new ArrayBuffer[X](); a ++= sequence; a }
}

abstract class DiscreteSeqDomain extends Domain[Seq[DiscreteValue]] {
  def elementDomain: DiscreteDomain
}
abstract class DiscreteSeqVariable extends SeqVar[DiscreteValue] with VarAndElementType[DiscreteSeqVariable,DiscreteValue] {
  def domain: DiscreteSeqDomain
  def appendInt(i:Int): Unit = _seq += domain.elementDomain.getValue(i)
}

class CategoricalSeqDomain[T] extends DiscreteSeqDomain with Domain[Seq[CategoricalValue[T]]] {
  lazy val elementDomain: CategoricalDomain[T] = new CategoricalDomain[T]
}
abstract class CategoricalSeqVariable[T] extends DiscreteSeqVariable with VarAndElementType[CategoricalSeqVariable[T],CategoricalValue[T]] {
  def domain: CategoricalSeqDomain[T]
  def appendCategory(x:T): Unit = _seq += domain.elementDomain.getValue(x)
}

