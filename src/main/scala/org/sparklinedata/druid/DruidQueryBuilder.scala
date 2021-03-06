/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sparklinedata.druid

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable.{Map => MMap}
import org.apache.spark.sql.catalyst.expressions.{Expression, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical.Aggregate
import org.apache.spark.sql.types.DataType
import org.sparklinedata.druid.metadata.{DruidColumn, DruidRelationInfo}

import scala.collection.mutable

/**
 *
 * @param drInfo
 * @param queryIntervals
 * @param referencedDruidColumns
 * @param dimensions
 * @param limitSpec
 * @param havingSpec
 * @param granularitySpec
 * @param filterSpec
 * @param aggregations
 * @param postAggregations
 * @param projectionAliasMap map from projected alias name to underlying column name.
 * @param outputAttributeMap list of output Attributes with the ExprId of the Attribute they
 *                           represent, the DataType in the original Plan and the DataType
 *                           from Druid.
 * @param aggExprToLiteralExpr for expressions that represent a 'null' value for
 *                             a this GroupingSet or represent the 'grouping__id'
 *                             columns, this is a map to the Literal value that is
 *                             filled in the Projection above the DruidRDD.
 * @param aggregateOper
 * @param curId
 */
case class DruidQueryBuilder(val drInfo: DruidRelationInfo,
                             queryIntervals: QueryIntervals,
                             referencedDruidColumns : MMap[String,DruidColumn] = MMap(),
                             dimensions: List[DimensionSpec] = Nil,
                             limitSpec: Option[LimitSpec] = None,
                             havingSpec: Option[HavingSpec] = None,
                             granularitySpec: Either[String, GranularitySpec] = Left("all"),
                             filterSpec: Option[FilterSpec] = None,
                             aggregations: List[AggregationSpec] = Nil,
                             postAggregations: Option[List[PostAggregationSpec]] = None,
                             projectionAliasMap: Map[String, String] = Map(),
                             outputAttributeMap:
                             Map[String, (Expression, DataType, DataType, String)] = Map(),
                            // avg expressions to perform in the Project Operator
                            // on top of Druid PhysicalScan
                             avgExpressions : Map[Expression, (String, String)] = Map(),
                             aggExprToLiteralExpr: Map[Expression, Expression] = Map(),
                             aggregateOper: Option[Aggregate] = None,
                             curId: AtomicLong = new AtomicLong(-1),
                             origProjList : Option[Seq[NamedExpression]] = None,
                             origFilter : Option[Expression] = None,
                             hasUnpushedProjections : Boolean = false,
                             hasUnpushedFilters : Boolean = false) {

  def hasUnpushedExpressions = hasUnpushedProjections || hasUnpushedFilters

  def dimension(d: DimensionSpec) = {
    this.copy(dimensions = (dimensions :+ d))
  }

  def limit(l: LimitSpec) = {
    this.copy(limitSpec = Some(l))
  }

  def having(h: HavingSpec) = {
    this.copy(havingSpec = Some(h))
  }

  def granularity(g: GranularitySpec) = {
    this.copy(granularitySpec = Right(g))
  }

  def filter(f: FilterSpec) = filterSpec match {
    case Some(f1: FilterSpec) =>
      this.copy(filterSpec = Some(LogicalFilterSpec("and", List(f1, f))))
    case None => this.copy(filterSpec = Some(f))
  }

  def aggregate(a: AggregationSpec) = {
    this.copy(aggregations = (aggregations :+ a))
  }

  def postAggregate(p: PostAggregationSpec) = postAggregations match {
    case None => this.copy(postAggregations = Some(List(p)))
    case Some(pAs) => this.copy(postAggregations = Some(pAs :+ p))
  }

  def interval(iC: IntervalCondition): Option[DruidQueryBuilder] = iC.typ match {
    case IntervalConditionType.LT =>
      queryIntervals.ltCond(iC.dt).map(qI => this.copy(queryIntervals = qI))
    case IntervalConditionType.LTE =>
      queryIntervals.ltECond(iC.dt).map(qI => this.copy(queryIntervals = qI))
    case IntervalConditionType.GT =>
      queryIntervals.gtCond(iC.dt).map(qI => this.copy(queryIntervals = qI))
    case IntervalConditionType.GTE =>
      queryIntervals.gtECond(iC.dt).map(qI => this.copy(queryIntervals = qI))
  }

  def outputAttribute(nm: String, e: Expression, originalDT: DataType,
                      druidDT: DataType, tfName: String = null) = {
    val tf = if (tfName == null) DruidValTransform.getTFName(druidDT) else tfName
    this.copy(outputAttributeMap = outputAttributeMap + (nm ->(e, originalDT, druidDT, tf)))
  }

  def avgExpression(e: Expression, sumAlias : String, cntAlias : String) = {
    this.copy(avgExpressions = avgExpressions + (e ->(sumAlias, cntAlias)))
  }

  def aggregateOp(op: Aggregate) = this.copy(aggregateOper = Some(op))

  def nextAlias: String = s"alias${curId.getAndDecrement()}"

  def nextAlias(cn: String): String = {
    var oAttrName = cn + nextAlias
    while (drInfo.sourceToDruidMapping.contains(oAttrName)) {
      oAttrName = cn + nextAlias
    }
    oAttrName
  }

  def isDruidNonTimeDimension(name : String) : Boolean = {
    druidColumn(name).map(_.isDimension(true)).getOrElse(false)
  }

  def druidColumn(name: String): Option[DruidColumn] = {
    drInfo.sourceToDruidMapping.get(projectionAliasMap.getOrElse(name, name)).map { dc =>
      referencedDruidColumns(name) = dc
      dc
    }
  }

  def addAlias(alias: String, col: String) = {
    val dColNm = projectionAliasMap.getOrElse(col, col)
    this.copy(projectionAliasMap = (projectionAliasMap + (alias -> dColNm)))
  }

  def orderBy(dimName: String, ascending: Boolean): DruidQueryBuilder = limitSpec match {
    case Some(LimitSpec(t, l, columns)) => limit(LimitSpec(t, l,
      columns :+ new OrderByColumnSpec(dimName, ascending)))
    case None => limit(new LimitSpec(Int.MaxValue,
      new OrderByColumnSpec(dimName, ascending)))
  }

  def limit(amt: Int): Option[DruidQueryBuilder] = limitSpec match {
    case Some(LimitSpec(t, l, columns)) if (l == Int.MaxValue || l == amt) =>
      Some(limit(LimitSpec(t, amt, columns)))
    case _ => None
  }

  /**
    * currently we don't transform queries with [[LimitSpec]] or [[HavingSpec]] into
    * post DruidOperations in Spark.
    *
    * @return
    */
  def canPushToHistorical = !(
    postAggregations.isDefined ||
    limitSpec.isDefined ||
    havingSpec.isDefined
    )

}

object DruidQueryBuilder {
  def apply(drInfo: DruidRelationInfo) =
    new DruidQueryBuilder(drInfo, new QueryIntervals(drInfo))
}
