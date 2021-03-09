/*
 * Copyright 2021 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.labs.gis.ml

import org.apache.spark.ml.param.{IntParam, Param, ParamValidators, Params}
import org.apache.spark.sql.types._

/**
 * Creating models in Spark requires extending an interface that is common between Estimator and Model
 * This interface indicates all parameters to read, save and set
 */
trait GeoscanParams extends Params {

  final val epsilon = new IntParam(this, "epsilon", "The maximum distance between two points in meters", ParamValidators.gt(1))
  final val minPts = new IntParam(this, "minPts", "The Minimum number of points within a distance of epsilon", ParamValidators.gt(1))
  final val latitudeCol: Param[String] = new Param[String](this, "latitudeCol", "latitude column name")
  final val longitudeCol: Param[String] = new Param[String](this, "longitudeCol", "longitude column name")
  final val predictionCol: Param[String] = new Param[String](this, "predictionCol", "prediction column name")
  final val groupedCol: Param[String] = new Param[String](this, "groupedCol", "column to group by to get personalized clusters")

  setDefault(groupedCol, "")
  setDefault(predictionCol, "predicted")
  setDefault(latitudeCol, "latitude")
  setDefault(longitudeCol, "longitude")
  setDefault(epsilon, 50)
  setDefault(minPts, 3)

  final def getPredictionCol: String = $(predictionCol)

  final def getGroupedCol: String = $(groupedCol)

  final def getLatitudeCol: String = $(latitudeCol)

  final def getLongitudeCol: String = $(longitudeCol)

  final def getEpsilon: Int = $(epsilon)

  final def getMinPts: Int = $(minPts)

  protected def validateAndTransformSchema(schema: StructType): StructType = {
    checkColumnType(schema, $(latitudeCol), DoubleType)
    checkColumnType(schema, $(longitudeCol), DoubleType)
    appendColumn(schema, $(predictionCol), StringType)
  }

  def appendColumn(schema: StructType, colName: String, dataType: DataType, nullable: Boolean = false): StructType = {
    if (colName.isEmpty) return schema
    appendColumn(schema, StructField(colName, dataType, nullable))
  }

  def appendColumn(schema: StructType, col: StructField): StructType = {
    require(!schema.fieldNames.contains(col.name), s"Column ${col.name} already exists.")
    StructType(schema.fields :+ col)
  }

  def checkColumnType(schema: StructType, colName: String, dataType: DataType, msg: String = ""): Unit = {
    val actualDataType = schema(colName).dataType
    val message = if (msg != null && msg.trim.length > 0) " " + msg else ""
    require(actualDataType.equals(dataType), s"Column $colName must be of type $dataType but was actually $actualDataType.$message")
  }
}
