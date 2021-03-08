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

import org.apache.commons.lang3.StringUtils
import org.apache.http.annotation.Experimental
import org.apache.spark.ml.Estimator
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{DefaultParamsReadable, DefaultParamsWritable, Identifiable}
import org.apache.spark.sql.functions.{col, collect_list, udf}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.Dataset
import scalax.collection.Graph
import scalax.collection.edge.WUnDiEdge

/**
 * Following the [[org.apache.spark.ml.Estimator]] interface of Spark ML, we embed our GEOSCAN logic as a pipeline object
 * This enables both parameters and model serialization / deserialization using Writable and Readable interfaces
 * @param uid is automatically created by Spark ML framework
 */
@Experimental
class GeoscanPersonalized(override val uid: String) extends Estimator[GeoscanPersonalizedModel] with DefaultParamsWritable with GeoscanParams {

  override def copy(extra: ParamMap): Estimator[GeoscanPersonalizedModel] = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    require(StringUtils.isNotEmpty($(groupedCol)), "Grouped column must be provided for personalized model")
    require(schema.fieldNames.contains($(groupedCol)), s"Column ${$(groupedCol)} does not exist.")
    validateAndTransformSchema(schema)
  }

  /**
   * The core of our GEOSCAN algorithm, operating on an entire dataframe
   * @param dataset the input dataframe containing latitude and longitudes
   * @return a trained [[com.databricks.labs.gis.ml.GeoscanModel]] model that contains all clusters as concave shapes of non overlapping H3 hexagons
   */
  override def fit(dataset: Dataset[_]): GeoscanPersonalizedModel = {

    val train_model = udf((xs: Seq[Double], ys: Seq[Double]) => {
      val pts = xs.zip(ys).map({ case (lat, lng) => GeoPoint(lat, lng)})
      val model = train(pts)
      model.toGeoJson
    })

    val trained_df = dataset.groupBy($(groupedCol)).agg(
        collect_list(col($(latitudeCol))).alias($(latitudeCol)),
        collect_list(col($(longitudeCol))).alias($(longitudeCol))
      )
      .withColumn($(predictionCol), train_model(col($(latitudeCol)), col($(longitudeCol))))
      .drop($(latitudeCol), $(longitudeCol))

    copyValues(new GeoscanPersonalizedModel(uid, trained_df).setParent(this))
  }

  def getOptimalPrecision: Int = {
    val precision = GeoUtils.getOptimalResolution($(epsilon))
    require(precision.nonEmpty, "Could not infer precision from epsilon value")
    precision.get
  }

  def setLatitudeCol(value: String): this.type = set(latitudeCol, value)

  def setLongitudeCol(value: String): this.type = set(longitudeCol, value)

  def setPredictionCol(value: String): this.type = set(predictionCol, value)

  def setGroupedCol(value: String): this.type  = set(groupedCol, value)

  def setEpsilon(value: Int): this.type = set(epsilon, value)

  def setMinPts(value: Int): this.type = set(minPts, value)

  def this() = this(Identifiable.randomUID("geoscan"))

  /**
   * This is the same principle as above, but operated in memory. We rely on [[scalax.collection.Graph]] instead of GraphX
   * @param data The input collection of points (with latitude and longitude)
   * @return all defined clusters as cluster ID and their respective H3 tiles
   */
  private def train(data: Traversable[GeoPoint]): GeoShape = {

    val precision = getOptimalPrecision

    // *****************************************************************
    // Step1: Expand and conquer, beating DBScan O(2) complexity with H3
    // *****************************************************************

    val edges = data
      .flatMap { point => GeoUtils.fillRadius(point.toH3(precision), $(epsilon)).map(_ -> point) }
      .groupBy { case (h3, _) => h3 }
      .values.toSeq
      .map { it => it.map({ case (_, point) => point }) }
      .flatMap { it =>
        val xs = it.toSeq
        xs.flatMap { x => xs.filter { y => x.hashCode() < y.hashCode() }.map { y => (x, y) } }
      }
      .map { case (p1, p2) => (p1, p2, math.abs(GeoUtils.haversine(p1, p2))) }
      .filter { case (_, _, dist) => dist <= $(epsilon) }
      .distinct
      .map { case (p1, p2, dist) => WUnDiEdge(p1, p2)(dist) }

    // ************************************************************
    // Step2: Executing core DBScan logic as simple graph operation
    // ************************************************************

    val graph: Graph[GeoPoint, WUnDiEdge] = Graph.from(List(), edges)
    val corePoints = graph.filter(graph.having(node = n => n.outDegree >= $(minPts)))
    val clusters = corePoints.componentTraverser().toList.sortBy(_.nodes.size)

    // *************************************
    // Step3: Computing concave hulls shapes
    // *************************************

    val model = clusters.map({ component  =>
      GeoUtils.convexHull(component.nodes.map(_.value).toList)
    }).filter(_.nonEmpty).zipWithIndex.map({ case (points, id) =>
      GeoCluster(id, points)
    })

    GeoShape(model)
  }

}

/**
 * We create our Estimator companion object
 * By extending [[org.apache.spark.ml.util.DefaultParamsReadable]] we allow serialization / deserialization of our parameters
 */
object GeoscanPersonalized extends DefaultParamsReadable[GeoscanPersonalized] {
  override def load(path: String): GeoscanPersonalized = super.load(path)
}


