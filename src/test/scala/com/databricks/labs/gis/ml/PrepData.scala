///*
// * Copyright 2019 Databricks, Inc.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package com.databricks.labs.gis.ml
//
//import java.io.{File, FileOutputStream, PrintWriter}
//import java.util.UUID
//
//import org.scalatest.FunSuite
//
//import scala.io.Source
//import scala.util.Random
//
//class PrepData extends FunSuite {
//
//  // THIS IS NOT A TEST, BUT GENERATES DATA TO VALIDATE AT SCALE
//  test("Generating geo points in multiple clusters") {
//
//    val resolution = 13
//    val shape = GeoShape.fromGeoJson(Source.fromInputStream(this.getClass.getResourceAsStream("/nyc_shapes.geojson")).getLines().mkString(""))
//    val masks = shape.clusters.zipWithIndex.map({ case (c, id) =>
//      (id, GeoUtils.polyFill(c.points, resolution))
//    }).toMap
//
//    val region = GeoShape.fromGeoJson(Source.fromInputStream(this.getClass.getResourceAsStream("/nyc.geojson")).getLines().mkString(""))
//    val noise = region.clusters.flatMap({ c =>
//      GeoUtils.polyFill(c.points, resolution)
//    })
//
//    val pw = new PrintWriter(new FileOutputStream(new File("nyc_shapes.csv")))
//
//    (1 to 200).flatMap(_ => {
//
//      val user = UUID.randomUUID().toString
//
//      val user_points = (1 to Random.nextInt(9) + 1).map(_ => {
//        Random.nextInt(masks.size)
//      }).flatMap(user_cluster_id => {
//        val mask = masks(user_cluster_id)
//        Random.shuffle(mask).take(10 + Random.nextInt(500)).map(h3 => {
//          GeoUtils.h3ToGeo(h3)
//        })
//      })
//
//      val user_random_points = Random.shuffle(noise).take((user_points.size * 0.3).toInt).map(GeoUtils.h3ToGeo)
//
//      (user_points ++ user_random_points).map(geo => {
//        user + "," + geo.latitude + "," + geo.longitude + "," + gen_amount()
//      })
//
//    }).foreach(t => {
//      pw.write(t)
//      pw.write("\n")
//    })
//
//    pw.close()
//
//  }
//
//
//  def gen_amount(): Double = {
//    val amt = (5 + (195 * Random.nextDouble()))
//    BigDecimal(amt).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
//  }
//
//}
