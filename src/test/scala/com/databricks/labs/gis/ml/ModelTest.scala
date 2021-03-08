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

import java.io.File
import java.nio.file.Files

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.scalatest.{FlatSpec, Matchers}

import scala.io.Source

class ModelTest extends FlatSpec with Matchers {

  val tempDir: File = Files.createTempDirectory("geoscan").toFile
  val modelDir: File = new File(tempDir, "model")
  val modelPersonalizedDir: File = new File(tempDir, "personalized")
  val radius = 100
  val precision: Int = GeoUtils.getOptimalResolution(radius).get
  val minPts = 3
  val text: Seq[String] = Source.fromInputStream(this.getClass.getResourceAsStream("/nyc.csv")).getLines().toList

  "A Dataframe of London data points" should "be grouped into 144 concave clusters" in {

    Logger.getLogger("akka").setLevel(Level.OFF)
    Logger.getLogger("org").setLevel(Level.OFF)

    val spark = SparkSession.builder().master("local[1]").appName("geoscan").getOrCreate()
    import spark.implicits._

    val points = text.map(s => {
      s.split(",") match {
        case Array(lat, lon, amt, user) => (lat.toDouble, lon.toDouble, amt.toDouble, user)
      }
    }).toDF("latitude", "longitude", "amount", "user")

    val model = new Geoscan()
      .setLatitudeCol("latitude")
      .setLongitudeCol("longitude")
      .setPredictionCol("cluster")
      .setEpsilon(radius)
      .setMinPts(minPts)
      .fit(points)

    // Ensure we found our 18 clusters
    model.getShape.clusters.length should be(144)

    // Show model tiles
    model.getTiles(12).show()

    // Serialize to disk
    model.save(modelDir.toString)

    // Ensure model was correctly serialized
    val retrieved = GeoscanModel.load(modelDir.toString)

    // Ensure data was correctly serialized
    retrieved.getShape.clusters.length should be(144)

    // Ensure metadata was correctly serialized
    retrieved.getUid should be(model.uid)
    retrieved.getLatitudeCol should be(model.getLatitudeCol)
    retrieved.getLongitudeCol should be(model.getLongitudeCol)
    retrieved.getPredictionCol should be(model.getPredictionCol)
    retrieved.getEpsilon should be(model.getEpsilon)
    retrieved.getMinPts should be(model.getMinPts)

    // Quick summary
    retrieved.transform(points).groupBy("cluster").count().show()

  }

  "A grouped Dataframe of London data points" should "be executed in parallel" in {

    Logger.getLogger("akka").setLevel(Level.OFF)
    Logger.getLogger("org").setLevel(Level.OFF)

    val spark = SparkSession.builder().master("local[1]").appName("geoscan").getOrCreate()
    import spark.implicits._

    val points = text.map(s => {
      s.split(",") match {
        case Array(lat, lon, amt, user) => (lat.toDouble, lon.toDouble, amt.toDouble, user)
      }
    }).toDF("latitude", "longitude", "amount", "user")

    val model = new GeoscanPersonalized()
      .setLatitudeCol("latitude")
      .setLongitudeCol("longitude")
      .setPredictionCol("cluster")
      .setGroupedCol("user")
      .setEpsilon(radius)
      .setMinPts(minPts)
      .fit(points)

    // Display model shapes
    model.toGeoJson.show()

    // Show model tiles
    model.getTiles(12).select("user", "cluster", "h3").show()

    // Serialize to disk
    model.save(modelPersonalizedDir.toString)

    // Ensure model was correctly serialized
    val retrieved = GeoscanPersonalizedModel.load(modelPersonalizedDir.toString)

    // Ensure data was correctly serialized
    retrieved.transform(points).show()
    val users = points.rdd.map(_.getAs[String]("user")).collect().toSet
    retrieved.transform(points).rdd.map(_.getAs[String]("user")).collect().toSet should be(users)

  }

}
