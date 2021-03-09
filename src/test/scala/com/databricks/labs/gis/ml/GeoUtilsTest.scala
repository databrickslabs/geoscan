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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class GeoUtilsTest extends AnyFlatSpec with Matchers {

  "Optimal resolution" should "be derived from epsilon" in {
    GeoUtils.getOptimalResolution(1) should be(Some(15)) // 1m
    GeoUtils.getOptimalResolution(10) should be(Some(12)) // 10m
    GeoUtils.getOptimalResolution(100) should be(Some(10)) // 100m
    GeoUtils.getOptimalResolution(1000) should be(Some(8)) // 1km
    GeoUtils.getOptimalResolution(10000) should be(Some(5)) // 10km
    GeoUtils.getOptimalResolution(100000) should be(Some(3)) // 100km
    GeoUtils.getOptimalResolution(1000000) should be(Some(1)) // 100km
    GeoUtils.getOptimalResolution(10000000) should be(empty) // 1000km
  }

  "A convex hull" should "be generated" in {
    val core = List(GeoPoint(1,1), GeoPoint(1,-1), GeoPoint(-1,-1), GeoPoint(-1, 1))
    val additional = (1 to 100).map(_ => {
      val sig = if (Random.nextBoolean()) 1 else -1
      GeoPoint(sig * Random.nextDouble(), sig * Random.nextDouble())
    })
    GeoUtils.convexHull(core ++ additional) should be(List(
      GeoPoint(-1.0,-1.0),
      GeoPoint(-1.0,1.0),
      GeoPoint(1.0,1.0),
      GeoPoint(1.0,-1.0),
      GeoPoint(-1.0,-1.0))
    )

  }

  "A circle of 10 meters" should "be covered with 7 tiles of precision 12" in {
    val h3 = GeoPoint(40.7128, -74.0060).toH3(12)
    GeoUtils.fillRadius(h3, 10).length should be(7)
  }

  "Distance between 2 points" should "be expressed in meters" in {
    val p1 = GeoPoint(40.7128, -74.0060)
    val p2 = GeoPoint(40.7128, -75.0060)
    assert(GeoUtils.haversine(p1, p2) < 100000)
  }

  "A geocoordinate" should "be encoded in h3" in {
    val pt = GeoPoint(40.712800, -74.005996)
    pt.toH3(15) should be(644754748784796037L)
  }

  "A circle" should "be covered in h3" in {
    val pt1 = GeoPoint(40.712800, -74.005996).toH3(9)
    GeoUtils.fillRadius(pt1, 100).size should be(7)
    val pt2 = GeoPoint(40.712800, -74.005996).toH3(8)
    GeoUtils.fillRadius(pt2, 2000).size should be(26)
  }

  "A polygon" should "be covered with h3 tiles" in {
    import collection.JavaConverters._
    val pt = GeoPoint(40.78411592042219, -73.94991874694824).toH3(9)

    // Instead of creating a random polygon, we create a polygon for a H3 point at low resolution..
    val polygon = GeoUtils.convexHull(H3.instance.h3ToGeoBoundary(pt).asScala.toList.map(n => n.toGeoPoint))

    // that we fill with H3 of higher resolution. A first layer should contain a center and 6 neighbours
    GeoUtils.polyFill(polygon, 10, 0).size should be(7)
    GeoUtils.polyFill(polygon, 10, 1).size should be(19)
    GeoUtils.polyFill(polygon, 10, 2).size should be(37)
  }

}
