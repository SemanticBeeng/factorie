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

package cc.factorie.app.chain

import cc.factorie._
import cc.factorie.la._
import cc.factorie.optimize._
import cc.factorie.app.chain.infer._
import scala.collection.mutable.{ListBuffer,ArrayBuffer, Map}
import java.io.File
import cc.factorie.la.DenseTensor1
import org.junit.Assert._
import scala.collection.mutable.LinkedHashMap
import cc.factorie.util.DoubleAccumulator

class ChainModel[Label<:LabeledMutableDiscreteVarWithTarget[_], Features<:CategoricalTensorVar[String], Token<:Observation[Token]]
(val labelDomain:CategoricalDomain[String],
 val featuresDomain:CategoricalTensorDomain[String],
 val labelToFeatures:Label=>Features,
 val labelToToken:Label=>Token,
 val tokenToLabel:Token=>Label) 
 (implicit lm:Manifest[Label], fm:Manifest[Features], tm:Manifest[Token])
extends ModelWithContext[IndexedSeq[Label]] //with Trainer[ChainModel[Label,Features,Token]]
{
  val labelClass = lm.erasure
  val featureClass = fm.erasure
  val tokenClass = tm.erasure
  object bias extends DotFamilyWithStatistics1[Label] {
    factorName = "Label"
    lazy val weights = new la.DenseTensor1(labelDomain.size)
  }
  object obs extends DotFamilyWithStatistics2[Label,Features] {
    factorName = "Label,Token"
    lazy val weights = new la.DenseTensor2(labelDomain.size, featuresDomain.dimensionSize)
  }
  object markov extends DotFamilyWithStatistics2[Label,Label] {
    factorName = "Label,Label"
    lazy val weights = new la.DenseTensor2(labelDomain.size, labelDomain.size)
  }
  object obsmarkov extends DotFamilyWithStatistics3[Label,Label,Features] {
    factorName = "Label,Label,Token"
    lazy val weights = if (useObsMarkov) new la.DenseTensor3(labelDomain.size, labelDomain.size, featuresDomain.dimensionSize) else new la.DenseTensor3(1, 1, 1)
  }
  var useObsMarkov = false
  override def families = if (useObsMarkov) Seq(bias, obs, markov, obsmarkov) else Seq(bias, obs, markov)

  def serialize(prefix: String) {
    val modelFile = new File(prefix + "-model")
    if (modelFile.getParentFile ne null)
      modelFile.getParentFile.mkdirs()
    BinaryCubbieFileSerializer.serialize(new ModelCubbie(this), modelFile)
    val labelDomainFile = new File(prefix + "-labelDomain")
    BinaryCubbieFileSerializer.serialize(new CategoricalDomainCubbie(labelDomain), labelDomainFile)
    val featuresDomainFile = new File(prefix + "-featuresDomain")
    BinaryCubbieFileSerializer.serialize(new CategoricalDomainCubbie(featuresDomain.dimensionDomain), featuresDomainFile)
  }

  def deSerialize(prefix: String) {
    val labelDomainFile = new File(prefix + "-labelDomain")
    assert(labelDomainFile.exists(), "Trying to load inexistent label domain file: '" + prefix + "-labelDomain'")
    val labelDomainCubbie = new CategoricalDomainCubbie(labelDomain)
    BinaryCubbieFileSerializer.deserialize(labelDomainCubbie, labelDomainFile)
    val featuresDomainFile = new File(prefix + "-featuresDomain")
    assert(featuresDomainFile.exists(), "Trying to load inexistent label domain file: '" + prefix + "-featuresDomain'")
    val featuresDomainCubbie = new CategoricalDomainCubbie(featuresDomain.dimensionDomain)
    BinaryCubbieFileSerializer.deserialize(featuresDomainCubbie, featuresDomainFile)
    val modelFile = new File(prefix + "-model")
    assert(modelFile.exists(), "Trying to load inexisting model file: '" + prefix + "-model'")
    assertEquals(markov.weights.length, labelDomain.length * labelDomain.length)
    BinaryCubbieFileSerializer.deserialize(new ModelCubbie(this), modelFile)
  }

  def factorsWithContext(labels:IndexedSeq[Label]): Iterable[Factor] = {
    val result = new ListBuffer[Factor]
    for (i <- 0 until labels.length) {
      result += bias.Factor(labels(i))
      result += obs.Factor(labels(i), labelToFeatures(labels(i)))
      if (i > 0) {
        result += markov.Factor(labels(i-1), labels(i))
        if (useObsMarkov) result += obsmarkov.Factor(labels(i-1), labels(i), labelToFeatures(labels(i)))
      }
    }
    result
  }
  override def factors(variables:Iterable[Variable]): Iterable[Factor] = variables match {
    case variables:IndexedSeq[Label] if (variables.forall(v => labelClass.isAssignableFrom(v.getClass))) => factorsWithContext(variables)
    case _ => super.factors(variables)
  }
  def factors(v:Variable) = v match {
    case label:Label if (label.getClass eq labelClass) => {
      val result = new ArrayBuffer[Factor](4)
      result += bias.Factor(label)
      result += obs.Factor(label, labelToFeatures(label))
      val token = labelToToken(label)
      if (token.hasPrev) {
        result += markov.Factor(tokenToLabel(token.prev), label)
        if (useObsMarkov)
          result += obsmarkov.Factor(tokenToLabel(token.prev), label, labelToFeatures(label))
      }
      if (token.hasNext) {
        result += markov.Factor(label, tokenToLabel(token.next))
        if (useObsMarkov)
          result += obsmarkov.Factor(label, tokenToLabel(token.next), labelToFeatures(tokenToLabel(token.next)))
      }
      result
    }
  }
  
  
  trait ChainSummary extends Summary[DiscreteMarginal] {
    // Do we actually want the marginal of arbitrary sets of variables? -brian
    def marginal(vs:Variable*): DiscreteMarginal = null
    def marginal(v:Variable): DiscreteMarginal
    def expectations: WeightsTensor
  }

  // Inference
  def inferBySumProduct(labels:IndexedSeq[Label]): ChainSummary = {
    val summary = new ChainSummary {
      private val (_expectations, (__nodeMarginals, __edgeMarginals), _logZ) = ForwardBackward.featureExpectationsMarginalsAndLogZ(labels, obs, markov, bias, labelToFeatures)
      lazy private val _nodeMarginals = {
        val res = new LinkedHashMap[Variable, DiscreteMarginal]
        res ++= labels.zip(__nodeMarginals).map { case (l, m) => 
          l -> new DiscreteMarginal1(l, new DenseProportions1(m))
        }
      }
      lazy private val _edgeMarginals = {
        labels.zip(labels.drop(1)).zip(__edgeMarginals).map { case ((l1, l2), m) => 
          // m is label X label
          val ds = labelDomain.length
          val t = new DenseProportions2(ds, ds)
          var d1 = 0
          while (d1 < ds) {
	        var d2 = 0
            while (d2 < ds) {
              t(d1, d2) += m(d1 * ds + d2)
              d2 += 1
            }
	        d1 += 1
          }
          new DiscreteMarginal2(l1, l2, t)
        }.toArray
      }
      def expectations = _expectations
      override def logZ = _logZ
      def marginals: Iterable[DiscreteMarginal] = _nodeMarginals.values
      def marginal(v: Variable): DiscreteMarginal = _nodeMarginals(v)
      override def marginal(_f: Factor): DiscreteMarginal = {
        val f = _f.asInstanceOf[DotFamily#Factor]
        if (f.family == bias || f.family == obs)
          marginal(f.variables.head)
        else if (f.family == markov)
          _edgeMarginals(labels.indexOf(f.variables.head))
        else
          throw new Error("ChainModel marginals can only be returned for ChainModel factors")
      }
    }
    summary
  }
  
  def inferByMaxProduct(labels:IndexedSeq[Label]): ChainSummary = {
    new ChainSummary {
      private val targetInts = Viterbi.search(labels, obs, markov, bias, labelToFeatures).toArray
      lazy private val variableTargetMap = labels.zip(targetInts).toMap
      private val variables = labels
      lazy private val _marginals = {
        val res = new LinkedHashMap[Variable, DiscreteMarginal]
        res ++= labels.zip(targetInts).map { case (l, t) => 
          l -> new DiscreteMarginal1(l, new SingletonProportions1(labelDomain.size, t))
        }
      }
      def marginals: Iterable[DiscreteMarginal] = _marginals.values
      def marginal(v: Variable): DiscreteMarginal = _marginals(v)
      override def setToMaximize(implicit d:DiffList): Unit = {
        var i = 0
        while (i < variables.length) {
          variables(i).set(targetInts(i))(d)
          i += 1
        }
      }
      def expectations = null
      override def marginal(_f: Factor): DiscreteMarginal = {
        val f = _f.asInstanceOf[DotFamily#Factor]
        if (f.family == bias || f.family == obs)
          marginal(f.variables.head)
        else if (f.family == markov) {
          val f2 = _f.asInstanceOf[Factor2[Label, Label]]
          val m = new DiscreteMarginal2(f2)
          m.proportions.+=(variableTargetMap(f2._1), variableTargetMap(f2._2), 1.0)
          m
        }
        else
          throw new Error("ChainModel marginals can only be returned for ChainModel factors")
      }
    }
  }

  object MarginalInference extends Infer {
    override def infer(variables:Iterable[Variable], model:Model, summary:Summary[Marginal] = null): Option[Summary[Marginal]] = Some(inferBySumProduct(variables.asInstanceOf[IndexedSeq[Label]]))
  }
  // Training
  val objective = new HammingTemplate[Label]
}

object ChainModel {
  class ChainExample[L <: LabeledMutableDiscreteVarWithTarget[_]](val labels:IndexedSeq[L]) extends Example[ChainModel[L,_,_]] {
    def accumulateExampleInto(model: ChainModel[L, _, _], gradient: WeightsTensorAccumulator, value: DoubleAccumulator, margin:DoubleAccumulator): Unit = {
      if (labels.size == 0) return
      val summary = model.inferBySumProduct(labels)

      if (value != null) { 
        var incr = 0.0;
        model.factors(labels).foreach(f => incr += f.assignmentScore(TargetAssignment))
        value.accumulate(incr - summary.logZ) 
      } 
      
      if (gradient != null) {
        model.factors(labels).asInstanceOf[Iterable[DotFamily#Factor]].foreach(factor => {
          gradient.accumulate(factor.family, factor.assignmentStatistics(TargetAssignment))
        })
      }

      for (family <- summary.expectations.families)
	    gradient.accumulate(family, summary.expectations(family), -1.0)
    }
  }
  
  def createChainExample[L <: LabeledMutableDiscreteVarWithTarget[_]](labels:IndexedSeq[L]) = new ChainExample(labels)
}