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

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.Model
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{MLWriter, _}
import org.apache.spark.sql.functions.{col, explode, udf}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, SQLContext}

/**
 * Spark model containing all clusters and their respective H3 tiles
 * @param uid unique identifier set by Spark framework
 * @param shapes the collection of clusters and their respective points as geojson objects
 */
class GeoscanPersonalizedModel(override val uid: String, shapes: DataFrame) extends Model[GeoscanPersonalizedModel] with GeoscanParams with MLWritable {

  def getUid: String = uid

  def toGeoJson: DataFrame = shapes

  def getPrecision: Int = {
    val precision = GeoUtils.getOptimalResolution($(epsilon))
    require(precision.nonEmpty, "Could not infer precision from epsilon value")
    precision.get
  }

  def getTiles(precision: Int, layers: Int = 0): DataFrame = {
    val precision_B = shapes.sparkSession.sparkContext.broadcast(precision)
    val layers_B = shapes.sparkSession.sparkContext.broadcast(layers)
    val to_tiles = udf((geoJson: String) => {
      GeoShape.fromGeoJson(geoJson).clusters.flatMap(cluster => {
        GeoUtils.polyFill(cluster.points, precision_B.value, layers_B.value).map(tile => {
          (cluster.id, f"${tile}%X")
        })
      })
    })

    shapes
      .withColumn("_tile", explode(to_tiles(col($(predictionCol)))))
      .withColumn($(predictionCol), col("_tile._1"))
      .withColumn("h3", col("_tile._2"))
      .select($(groupedCol), $(predictionCol), "h3")

  }

  override def copy(extra: ParamMap): GeoscanPersonalizedModel = defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    require(StringUtils.isNotEmpty($(groupedCol)), "Grouped column must be provided for personalized model")
    require(schema.fieldNames.contains($(groupedCol)), s"Column ${$(groupedCol)} does not exist.")
    validateAndTransformSchema(schema)
  }

  /**
   * Core logic of our inference. As the core of GEOSCAN logic relies on the use of `H3` polygons,
   * it becomes natural to leverage the same for model inference instead of bringing in extra GIS dependencies for
   * expensive point in polygons queries. Our geospatial query becomes a simple JOIN operation
   * @param dataset the input dataset to enrich with our model
   * @return the original dataset enriched with a cluster ID (or None)
   */
  override def transform(dataset: Dataset[_]): DataFrame = {

    // We find the best precision based on our epsilon value
    val precision = getPrecision
    val precision_B = shapes.sparkSession.sparkContext.broadcast(precision)

    // That we could join to incoming H3 points (created from a simple UDF)
    val to_h3 = udf((lat: Double, lng: Double) => {
      val h3 = H3.instance.geoToH3(lat, lng, precision_B.value)
      f"${h3}%X"
    })

    // Model inference becomes a simple JOIN operation
    dataset
      .withColumn("h3", to_h3(col($(latitudeCol)), col($(longitudeCol))))
      .join(getTiles(precision), List("h3", $(groupedCol)), "left_outer")
      .drop("h3")

  }

  /**
   * We create our own class to persist both data and metadata of our model
   * @return an instance of MLWriter interface
   */
  override def write: MLWriter = {
    new GeoscanPersonalizedModel.GeoscanPersonalizedModelWriter(this)
  }

}

/**
 * One of the annoying things in Spark is the fact that most of useful method are private and / or package restricted.
 * In order to save both data and metadata, we have to implement our own MLReader interface
 */
object GeoscanPersonalizedModel extends MLReadable[GeoscanPersonalizedModel] {

  /**
   * We tell Spark framework how to deserializa our model using our implementation of MLReader interface
   * @return an MLReader interface
   */
  override def read: MLReader[GeoscanPersonalizedModel] = new GeoscanPersonalizedModelReader()

  /**
   * We tell Spark framework how to serializa our model using our implementation of MLWriter interface
   * This consists in saving metadata as JSON and data as TSV
   * @param instance the model to save
   */
  class GeoscanPersonalizedModelWriter(instance: GeoscanPersonalizedModel) extends MLWriter {
    override protected def saveImpl(path: String): Unit = {
      ModelIOUtils.saveMetadata(instance, path, sc)
      saveData(instance, path)
    }

    /**
     * Given our model, we store data to disk as a simple TSV file
     * @param instance the model to save
     * @param path the path where to store our model (distributed file system)
     */
    def saveData(instance: GeoscanPersonalizedModel, path: String): Unit = {
      val dataPath = new Path(path, "data").toString
      instance.toGeoJson.write.format("parquet").save(dataPath)
    }
  }

  class GeoscanPersonalizedModelReader extends MLReader[GeoscanPersonalizedModel] {
    /**
     * We tell Spark framework how to deserialize our model using our implementation of MLReader interface
     * @param path where to load model from
     * @return a configured instance of our [[GeoscanPersonalizedModel]]
     */
    override def load(path: String): GeoscanPersonalizedModel = {
      val metadata = ModelIOUtils.loadMetadata(path, sc)
      val data = loadData(path, sqlContext)
      val instance = new GeoscanPersonalizedModel(metadata.uid, data)
      ModelIOUtils.getAndSetParams(instance, metadata)
      instance
    }

    /**
     * As we stored our model as TSV on distributed file system, we simple read as textFile and collect back to memory
     * @param path where to read data from
     * @param sqlContext the spark sql context, implicitly provided by Spark API
     * @return all our clusters and their respective tiles
     */
    def loadData(path: String, sqlContext: SQLContext): DataFrame = {
      val dataPath = new Path(path, "data").toString
      sqlContext.read.format("parquet").load(dataPath)
    }
  }

}
