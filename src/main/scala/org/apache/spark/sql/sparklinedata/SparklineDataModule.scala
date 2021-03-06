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

package org.apache.spark.sql.sparklinedata

import org.apache.spark.Logging
import org.apache.spark.sql.SQLConf.SQLConfEntry._
import org.apache.spark.sql.{SQLContext, Strategy}
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.catalyst.optimizer.Optimizer
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.{Rule, RuleExecutor}
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.hive.sparklinedata.{SparklineDataContext, SparklineDataParser, SparklineDruidCommandsParser}
import org.apache.spark.sql.planner.logical.DruidLogicalOptimizer
import org.apache.spark.sql.sources.druid.{DruidPlanner, DruidStrategy}
import org.apache.spark.sql.sparklinedata.shim.SparkShim
import org.sparklinedata.spark.dateTime.Functions

trait SparklineDataModule {

  /**
    * Function registrations.
    *
    * @param sqlContext
    */
  def registerFunctions(sqlContext : HiveContext) : Unit = {}

  /**
    * Any extra rules that should be added to the Logical Optimizer.
    * @return
    */
  def logicalRules : Seq[(String, SparkShim.RuleStrategy, Rule[LogicalPlan])] = Nil

  /**
    * An optinal parser extension
    *
    * @param sqlContext
    * @return
    */
  def parser(sqlContext : HiveContext) : Option[SparklineDataParser] = None

  /**
    * An optional transform of LogicalPlan generated by the parser.
    * These apply changes before any semantic analysis has happened.
    * Use it for very coarse transformations like:
    * - removing invalid sort expressions.
    *
    * @param sQLContext
    * @return
    */
  def parsedTransform(sQLContext: HiveContext) : Option[RuleExecutor[LogicalPlan]] = None

  def physicalRules(sqlContext : HiveContext) : Seq[Strategy] = Nil

}

object BaseModule extends SparklineDataModule {

  override def registerFunctions(sqlContext : HiveContext) = {
    Functions.register(sqlContext)
  }

  override def logicalRules : Seq[(String, SparkShim.RuleStrategy, Rule[LogicalPlan])] =
    DruidLogicalOptimizer.batches

  override def parser(sqlContext : HiveContext) : Option[SparklineDataParser] =
    Some(new SparklineDruidCommandsParser(sqlContext))

  override def physicalRules(sqlContext : HiveContext) : Seq[Strategy] = {
    val dP = DruidPlanner(sqlContext)
    Seq(new DruidStrategy(dP))
  }

}

class ModuleLoader(sqlContext : SparklineDataContext,
                   val modules : Seq[SparklineDataModule]) {

  def registerFunctions : Unit = {
    modules.foreach { m =>
      m.registerFunctions(sqlContext)
    }
  }

  def logicalOptimizer : Optimizer = {
    val batches = modules.flatMap(_.logicalRules)
    SparkShim.extendedlogicalOptimizer(sqlContext.conf, batches)
  }

  def addPhysicalRules : Unit = {
    val pRules = modules.flatMap(_.physicalRules(sqlContext))
    sqlContext.experimental.extraStrategies =
      pRules ++ sqlContext.experimental.extraStrategies
  }

  def parsers : Seq[SparklineDataParser] = {
    modules.flatMap(_.parser(sqlContext).toSeq)
  }

  def parserTransformers : Seq[RuleExecutor[LogicalPlan]] = {
    modules.flatMap(_.parsedTransform(sqlContext).toSeq)
  }

}

object ModuleLoader extends Logging {

  private var modules : Seq[SparklineDataModule] = _

  private def loadModules(sqlContext : SparklineDataContext) : Unit = synchronized {

    if (modules == null ) {

      val runtimeMirror = ScalaReflection.mirror
      val modulesToLoad = sqlContext.conf.getConf(ModuleLoader.SPARKLINE_MODULES)

      modules = Seq(BaseModule) ++
        modulesToLoad.map { m =>

          val module = runtimeMirror.staticModule(m)
          val obj = runtimeMirror.reflectModule(module)
          val o = obj.instance.asInstanceOf[SparklineDataModule]
          log.info(s"loaded sparklinedata module '$m'")
          o
        }
    }
  }

  val SPARKLINE_MODULES = seqConf[String](
    "spark.sparklinedata.modules",
    identity _,
    defaultValue = Some(Seq()),
    doc = "sparkline modules to load."
  )

  def apply(sqlContext : SparklineDataContext) : ModuleLoader = {
    loadModules(sqlContext)
    new ModuleLoader(sqlContext, modules)
  }
}