/*
 * Copyright 2019 Databricks, Inc.
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

import org.apache.http.annotation.Experimental
import org.apache.spark.graphx.{Edge, Graph}
import org.apache.spark.ml.Estimator
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{DefaultParamsReadable, DefaultParamsWritable, Identifiable}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Dataset, Row}

/**
 * Following the [[org.apache.spark.ml.Estimator]] interface of Spark ML, we embed our GEOSCAN logic as a pipeline object
 * This enables both parameters and model serialization / deserialization using Writable and Readable interfaces
 * @param uid is automatically created by Spark ML framework
 */
@Experimental
class Geoscan(override val uid: String) extends Estimator[GeoscanModel] with DefaultParamsWritable with GeoscanParams {

  override def copy(extra: ParamMap): Estimator[GeoscanModel] = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = validateAndTransformSchema(schema)

  /**
   * The core of our GEOSCAN algorithm, operating on an entire dataframe
   * @param dataset the input dataframe containing latitude and longitudes
   * @return a trained [[com.databricks.labs.gis.ml.GeoscanModel]] model that contains all clusters as concave shapes of non overlapping H3 hexagons
   */
  override def fit(dataset: Dataset[_]): GeoscanModel = {
    transformSchema(dataset.schema, logging = true)
    val hulls = train(dataset)
    copyValues(new GeoscanModel(uid, hulls).setParent(this))
  }

  def getOptimalPrecision: Int = {
    val precision = GeoUtils.getOptimalResolution($(epsilon))
    require(precision.nonEmpty, "Could not infer precision from epsilon value")
    precision.get
  }

  def setLatitudeCol(value: String): this.type = set(latitudeCol, value)

  def setLongitudeCol(value: String): this.type = set(longitudeCol, value)

  def setPredictionCol(value: String): this.type = set(predictionCol, value)

  def setEpsilon(value: Int): this.type = set(epsilon, value)

  def setMinPts(value: Int): this.type = set(minPts, value)

  def this() = this(Identifiable.randomUID("geoscan"))

  /**
   * Core GEOSCAN algorithm. For more information, please refer to README.md.
   * This is a 3 phases process:
   * - 1. we leverage H3 framework to group points in close vicinity, only keeping points no more than epsilon meters away
   * - 2. we apply graph analytics to filter out nodes with less than minPts connections
   * - 3. we compute convex hull and its concave shape
   * @param dataset The input dataset with latitude and longitude
   * @return all defined clusters as cluster ID and their respective H3 tiles
   */
  def train(dataset: Dataset[_]): GeoShape = {

    val data = dataset.select($(latitudeCol), $(longitudeCol)).rdd.map({ case Row(lat: Double, lon: Double) =>
      GeoPoint(lat, lon)
    })

    val precision = getOptimalPrecision
    val sc = data.sparkContext
    val precision_B = sc.broadcast(precision)
    val epsilon_B = sc.broadcast($(epsilon))
    val minPts_B = sc.broadcast($(minPts))

    // *****************************************************************
    // Step1: Expand and conquer, beating DBScan O(2) complexity with H3
    // *****************************************************************

    // Group points sharing at least 1 H3 (relatively close one another)
    // We know they are too far away (more than radius) if they do not share at least one polygon
    // Build edges where distance exactly within specified radius
    val edges = data
      .flatMap { point => GeoUtils.fillRadius(point.toH3(precision_B.value), epsilon_B.value).map(_ -> point) }
      .groupByKey()
      .values
      .flatMap { it =>
        val xs = it.toSeq
        xs.combinations(2).map(ys => (ys.head, ys.last))
      }
      .map { case (p1, p2) => (p1, p2, math.abs(GeoUtils.haversine(p1, p2))) }
      .filter { case (_, _, dist) => dist <= epsilon_B.value }
      .distinct
      .map { case (p1, p2, dist) => Edge(p1.toH3(), p2.toH3(), dist) }

    // ************************************************************
    // Step2: Executing core DBScan logic as simple graph operation
    // ************************************************************

    val graph: Graph[Long, Double] = Graph.fromEdges(edges, 0L)

    // A point needs to be connected to at least [minPts] points
    val clusters = graph
      .outerJoinVertices(graph.degrees)((_, point, deg) => (point, deg.getOrElse(0)))
      .subgraph(_ => true, (_, vData) => vData._2 >= minPts_B.value)
      .connectedComponents()
      .vertices
      .map { case (vId, cId) => (cId, GeoUtils.h3ToGeo(vId)) }
      .groupByKey()
      .values
      .sortBy(-_.size)

    // *************************************
    // Step3: Computing concave hulls shapes
    // *************************************

    val model = clusters.map({ points  =>
      GeoUtils.convexHull(points)
    }).filter(_.nonEmpty).zipWithIndex().map({ case (points, id) =>
      GeoCluster(id, points)
    }).collect().toList

    GeoShape(model)

  }

}

/**
 * We create our Estimator companion object
 * By extending [[org.apache.spark.ml.util.DefaultParamsReadable]] we allow serialization / deserialization of our parameters
 */
object Geoscan extends DefaultParamsReadable[Geoscan] {
  override def load(path: String): Geoscan = super.load(path)
}


