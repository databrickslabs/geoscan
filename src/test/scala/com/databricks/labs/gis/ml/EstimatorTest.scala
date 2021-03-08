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

import java.io.File
import java.nio.file.Files

import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.scalatest.{FlatSpec, Matchers}

class EstimatorTest extends FlatSpec with Matchers {

  val tempDir: File = Files.createTempDirectory("geoscan").toFile
  val modelDir: File = new File(tempDir, "pipeline")

  "Parameters of an estimator" should "be set" in {

    intercept[IllegalArgumentException] {
      new Geoscan().setEpsilon(-1)
    }

    intercept[IllegalArgumentException] {
      new Geoscan().setMinPts(1)
    }

  }

  it should "be persisted" in {

    Logger.getLogger("akka").setLevel(Level.OFF)
    Logger.getLogger("org").setLevel(Level.OFF)

    // We have to create a spark session to save pipeline
    SparkSession.builder().master("local[1]").appName("geoscan").getOrCreate()

    val geoscan = new Geoscan()
      .setEpsilon(1000)
      .setMinPts(2)
      .setLatitudeCol("lat")
      .setLongitudeCol("lng")
      .setPredictionCol("pred")

    geoscan.save(modelDir.toString)

    val retrieved = Geoscan.load(modelDir.toString)
    retrieved.uid should be(geoscan.uid)
    retrieved.latitudeCol should be(geoscan.latitudeCol)
    retrieved.longitudeCol should be(geoscan.longitudeCol)
    retrieved.predictionCol should be(geoscan.predictionCol)
    retrieved.epsilon should be(geoscan.epsilon)
    retrieved.minPts should be(geoscan.minPts)

  }

}
