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

package com.databricks.labs.gis

import com.uber.h3core.H3Core
import com.uber.h3core.util.GeoCoord
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s.{DefaultFormats, JArray}
import org.json4s.jackson.JsonMethods.{compact, parse, render}

package object ml {

  // @see: https://h3geo.org/docs/core-library/restable
  // We simply report the average area of each H3 dimension to find optimal precision of our model
  val H3_RESOLUTIONS: Array[Double] = Array(
    4250546.8477000 * 1000000L,
    607220.9782429 * 1000000L,
    86745.8540347 * 1000000L,
    12392.2648621 * 1000000L,
    1770.3235517 * 1000000L,
    252.9033645 * 1000000L,
    36.1290521 * 1000000L,
    5.1612932 * 1000000L,
    0.7373276 * 1000000L,
    0.1053325 * 1000000L,
    0.0150475 * 1000000L,
    0.0021496 * 1000000L,
    0.0003071 * 1000000L,
    0.0000439 * 1000000L,
    0.0000063 * 1000000L,
    0.0000009 * 1000000L
  )

  /**
   * Helper class to manipulate H3 objects into serializable case class
   * @param g the H3 geo point Java object
   */
  implicit class GeoCoordUtil(g: GeoCoord) {

    /**
     * Converting a H3 Java object into its helper case class
     * @return The [[com.databricks.labs.gis.ml.GeoPoint]] object
     */
    def toGeoPoint: GeoPoint = GeoPoint(g.lat, g.lng)
  }

  /**
   * Friendly and light weight case class to carry both latitude and longitude as a single object
   * @param latitude the latitude
   * @param longitude the longitude
   */
  case class GeoPoint(latitude: Double, longitude: Double) {
    /**
     * Converting a geopoint into its H3 representation given a resolution
     * @param resolution the H3 resolution to use (see above table)
     * @return the H3 projection as a long
     */
    def toH3(resolution: Int = 15): Long = H3.instance.geoToH3(latitude, longitude, resolution)

    /**
     * Convert a case class Geopoint to its H3 java object
     * @return the H3 java object GeoCoord
     */
    def toGeoCoord: GeoCoord = new GeoCoord(latitude, longitude)
  }

  case class GeoCluster(id: Long, points: List[GeoPoint]) {
    def getTiles(precision: Int): Seq[Long] = {
      GeoUtils.polyFill(points, precision)
    }
  }

  case class GeoShape(clusters: List[GeoCluster]) {

    /**
     * Given a H3 precision, we tile all polygons we've extracted so that geo file can be encoded and indexed on database
     * for faster lookup. This can be really handy when scoring millions of data points instead of complex points in polygon queries
     * @param precision the H3 precision to use
     * @return the list of all clusters and their non overlapping H3 hexagons
     */
    def getTiles(precision: Int): Seq[(Long, Long)] = {
      clusters.flatMap(cluster => {
        cluster.getTiles(precision).map(h3 => {
          (cluster.id, h3)
        })
      })
    }

    def toGeoJson: String = {
      val geojson =
        ("type" -> "FeatureCollection") ~
          ("features" -> clusters.map(cluster => {
            ("type" -> "Feature") ~
              ("id" -> cluster.id) ~
              ("properties" -> ("name" -> s"CLUSTER-${cluster.id}")) ~
              ("geometry" -> (
                ("type" -> "Polygon") ~
                  ("coordinates" -> List(cluster.points.map(p => List(p.longitude, p.latitude))))
                ))
          }))
      compact(render(geojson))
    }

  }

  object GeoShape {

    def fromGeoJson(jsonStr: String): GeoShape = {

      implicit val formats: DefaultFormats.type = DefaultFormats
      val geojson = parse(jsonStr)

      val clusters = geojson \ "features" match {
        case JArray(features) => features map { cluster =>
          val id = (cluster \ "id").extractOrElse[Long](-1L)
          val points = cluster \ "geometry" \ "coordinates" match {
            case JArray(List(jarr)) => jarr match {
              case JArray(xs) => xs map {
                case JArray(List(v1, v2)) => GeoPoint(v2.extract[Double], v1.extract[Double])
                case _ => throw new IllegalArgumentException("Coordinates should be in format [longitude,latitude]")
              }
              case _ => throw new IllegalArgumentException("Coordinates should be stored as an array of [longitude,latitude]")
            }
            case _ => throw new IllegalArgumentException("Coordinates should be written as a matrix")
          }
          GeoCluster(id, points)
        }
        case _ => throw new IllegalArgumentException("Features should contain coordinates")
      }
      GeoShape(clusters)
    }
  }

  /**
   * A serializable singleton object to efficiently get an H3Core instance across multiple workers
   */
  object H3 extends Serializable {
    @transient lazy val instance: H3Core = H3Core.newInstance();
  }

}
