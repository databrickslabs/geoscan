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

class GeoJsonTest extends AnyFlatSpec with Matchers {

  "A polygon" should "be be converted in GeoJson" in {
    val shape = GeoShape(List(GeoCluster(1, List(GeoPoint(1,1), GeoPoint(1,-1), GeoPoint(-1,-1), GeoPoint(-1, 1)))))
    val expected = """{"type":"FeatureCollection","features":[{"type":"Feature","id":1,"properties":{"name":"CLUSTER-1"},"geometry":{"type":"Polygon","coordinates":[[[1.0,1.0],[-1.0,1.0],[-1.0,-1.0],[1.0,-1.0]]]}}]}""".stripMargin
    shape.toGeoJson should be(expected)
    GeoShape.fromGeoJson(expected) should be(shape)
  }

}
