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

import com.uber.h3core.AreaUnit
import com.uber.h3core.util.GeoCoord
import scalax.collection.Graph
import scalax.collection.GraphEdge.UnDiEdge

object GeoUtils {

  /**
   * Given a shape defined as a list of points and a precision, we fill that shape with non overlapping H3 hexagons
   * Note that a lower precision would results in less hexagons but would not fit our shape well around its edges
   * whereas a higher precision would use a lot of memory to store much more H3 hexagons. Luckily, the precision is driven by our model
   * and controlled by its epsilon parameter
   * @param points the points defining our polygon
   * @param precision the precision used in defining H3 hexagons
   * @return the list of all H3 hexagons fitting into our shape
   */
  def polyFill(points: List[GeoPoint], precision: Int, extraLayer: Int = 0): Seq[Long] = {
    import scala.collection.JavaConverters._
    import scala.collection.mutable.ListBuffer
    val holes: java.util.List[java.util.List[GeoCoord]] = List().asJava
    val geocoords = ListBuffer(points.map(pt => new GeoCoord(pt.latitude, pt.longitude)): _*).asJava
    val tiles = H3.instance.polyfill(geocoords, holes, precision).asScala.map(Long2long)
    if(extraLayer == 0) {
      tiles
    } else {

      // Link all tiles to their neighbours, adding 1 additional layer at the edges of the polygon
      val edges = tiles.flatMap(tile => {
        kRing(tile, 1).map(neighbour => {
          UnDiEdge(tile, neighbour)
        })
      })
      val graph = Graph.from(List.empty[Long], edges)
      expandPolyFill(graph, extraLayer)
    }
  }

  @scala.annotation.tailrec
  def expandPolyFill(graph: Graph[Long, UnDiEdge], desiredLayers: Int, i: Int = 1): Seq[Long] = {

    if(i == desiredLayers)
      return graph.nodes.map(_.value).toSeq

    val newEdges = graph.nodes.filter(_.outDegree < 6).flatMap(border => {
      val tile = border.value
      kRing(tile, 1).map(neighbour => {
        UnDiEdge(tile, neighbour)
      })
    })

    expandPolyFill(graph ++ newEdges, desiredLayers, i + 1)
  }

  /**
   * Given an H3 hexagon, we find all its n degrees neighbours. Degree one consists in 6 H3 polygons
   * @param h3 the original point as an H3 polygon to get a ring of neighbors from
   * @param ring the degree of neighbours to return (1st layer surrounding our point, 2nd layer, etc)
   * @return the H3 hexagons as onion layer of our original point
   */
  def kRing(h3: Long, ring: Int): Seq[Long] = {
    import scala.collection.JavaConverters._
    H3.instance.kRing(h3, ring).asScala.map(Long2long)
  }

  /**
   * Haversine distance between 2 geo points.
   * @param p1 the Geopoint containing latitude / longitude of point 1
   * @param p2 the Geopoint containing latitude / longitude of point 2
   * @return the distance (in km) between 2 geo points
   */
  def haversine(p1: GeoPoint, p2: GeoPoint): Double = {
    val dLat = (p2.latitude - p1.latitude).toRadians
    val dLon = (p2.longitude - p1.longitude).toRadians
    val a = Math.pow(Math.sin(dLat / 2), 2) + Math.pow(Math.sin(dLon / 2), 2) * Math.cos(p1.latitude.toRadians) * Math.cos(p2.latitude.toRadians)
    val c = 2 * Math.asin(Math.sqrt(a))
    val EARTH_RADIUS: Double = 6371000.0
    EARTH_RADIUS * c
  }

  /**
   * Given a distance parameter, we retrieve the optimal H3 precision to fit a circle of radius with 2 rings
   * @param radius the radius in kilometers
   * @return the best resolution to fit exactly 2 rings of H3 hexagon within that circle
   */
  def getOptimalResolution(radius: Double): Option[Int] = {
    val circle = math.Pi * math.pow(radius, 2)
    H3_RESOLUTIONS.zipWithIndex.reverse.dropWhile({ case (area, _) =>
      7 * area < circle
    }).headOption.map(_._2)
  }

  /**
   * Given a point encoded as H3 polygon, we return the geo coordinates
   * @param h3 the encoded polygon
   * @return the geocoordinates
   */
  def h3ToGeo(h3: Long): GeoPoint = {
    H3.instance.h3ToGeo(h3).toGeoPoint
  }

  /**
   * Given a point encoded as H3 polygon, we return the geo coordinates
   * @param h3 the encoded polygon
   * @return the geocoordinates
   */
  def h3ToGeo(h3: String): GeoPoint = {
    H3.instance.h3ToGeo(h3).toGeoPoint
  }

  /**
   * Given a H3 location, we find all onion layers required to cover a circle of radius epsilon
   * @param h3 the initial starting position
   * @param epsilon the radius of the area to cover (in meters)
   * @return the list of all H3 required to tile a circle of size epsilon
   */
  def fillRadius(h3: Long, epsilon: Int): Seq[Long] = {
    val targetArea = math.Pi * math.pow(epsilon, 2)
    val polyArea = H3.instance.hexArea(H3.instance.h3GetResolution(h3), AreaUnit.m2)
    _fillRadius(1, h3, targetArea, polyArea, Seq.empty[Long])
  }

  @scala.annotation.tailrec
  private def _fillRadius(i: Int, h3: Long, targetArea: Double, polyArea: Double, fill: Seq[Long]): Seq[Long] = {
    if(fill.length * polyArea> targetArea) {
      fill
    } else {
      _fillRadius(i + 1, h3, targetArea, polyArea, fill ++ kRing(h3, i))
    }
  }

  /**
   * Simple helper method to create an H3 point from latitude, longitude and precision
   * @param lat the latitude of our point
   * @param lng the longitude of our point
   * @param precision the desired H3 precision
   * @return the Long value of our generated H3 hexagon
   */
  def geoToH3(lat: Double, lng: Double, precision: Int): Long = {
    H3.instance.geoToH3(lat, lng, precision)
  }

  /**
   * Core logic of convex hull algorithm. I've been using that code for a long time but could not trace its original source
   * However, the code of convex hull is mainstream and can be found in various places, such as https://rosettacode.org/wiki/Convex_hull
   */
  private def lineDistanceFunction(line: (GeoPoint, GeoPoint)): GeoPoint => Double = {
    line match {
      case (GeoPoint(lat1, lon1), GeoPoint(lat2, lon2)) =>
        val divider = Math.sqrt((lon2 - lon1) * (lon2 - lon1) + (lat2 - lat1) * (lat2 - lat1))
        val dy = lon2 - lon1
        val dx = lat2 - lat1
        val rest = lat2 * lon1 - lon2 * lat1
        (point: GeoPoint) => (dy * point.latitude - dx * point.longitude + rest) / divider
    }
  }

  /**
   * Core logic of convex hull algorithm. I've been using that code for a long time but could not trace its original source
   * However, the code of convex hull is mainstream and can be found in various places, such as https://rosettacode.org/wiki/Convex_hull
   */
  private def isInsideTriangleFunction(triangle: (GeoPoint, GeoPoint, GeoPoint)): GeoPoint => Boolean = {
    triangle match {
      case (GeoPoint(lat1, lon1), GeoPoint(lat2, lon2), GeoPoint(lat3, lon3)) => {
        val denominator = (lon2 - lon3) * (lat1 - lat3) + (lat3 - lat2) * (lon1 - lon3)
        val dy2y3 = lon2 - lon3
        val dx3x2 = lat3 - lat2
        val dy3y1 = lon3 - lon1
        val dx1x3 = lat1 - lat3
        (point: GeoPoint) => {
          lazy val a = (dy2y3 * (point.latitude - lat3) + dx3x2 * (point.longitude - lon3)) / denominator
          lazy val b = (dy3y1 * (point.latitude - lat3) + dx1x3 * (point.longitude - lon3)) / denominator
          lazy val c = 1 - a - b
          (0 <= a && a <= 1) && (0 <= b && b <= 1) && (0 <= c && c <= 1)
        }
      }
    }
  }

  /**
   * Core logic of convex hull algorithm. I've been using that code for a long time but could not trace its original source
   * However, the code of convex hull is mainstream and can be found in various places, such as https://rosettacode.org/wiki/Convex_hull
   */
  private def findLocalHull(set: Traversable[(GeoPoint, Double)], point1: GeoPoint, point2: GeoPoint): List[GeoPoint] = {

    if (set.isEmpty) return Nil

    val (maxDistancePoint, _) = set.foldLeft(set.head) { case (maxDistanceItem, item) =>
      if (Math.abs(item._2) > Math.abs(maxDistanceItem._2)) item else maxDistanceItem
    }

    val belongsFunc = isInsideTriangleFunction((point1, point2, maxDistancePoint))
    val pointsLeft = set.filter(p => (p._1 != maxDistancePoint) && !belongsFunc(p._1)).map(_._1)

    val distanceSet1Func = lineDistanceFunction((point1, maxDistancePoint))
    val distanceSet2Func = lineDistanceFunction((maxDistancePoint, point2))

    val set1 = pointsLeft.map(p => (p, distanceSet1Func(p))).filter(_._2 < 0)
    val set2 = pointsLeft.map(p => (p, distanceSet2Func(p))).filter(_._2 < 0)

    findLocalHull(set1, point1, maxDistancePoint) ::: (maxDistancePoint :: findLocalHull(set2, maxDistancePoint, point2))

  }

  /**
   * Core logic of convex hull algorithm. I've been using that code for a long time but could not trace its original source
   * However, the code of convex hull is mainstream and can be found in various places, such as https://rosettacode.org/wiki/Convex_hull
   * @param points the points to compute convex hull from
   * @return the ordered points of the convex hull
   */
  def convexHull(points: Traversable[GeoPoint]): List[GeoPoint] = {

    val leftPoint = points.foldLeft(points.head) {
      case (min, current) =>
        if (current.latitude < min.latitude) current else min
    }

    val rightPoint = points.foldLeft(points.head) {
      case (max, current) =>
        if (current.longitude > max.longitude) current else max
    }

    val distanceFuncSet = lineDistanceFunction((leftPoint, rightPoint))
    val pointsWithDistanceSet = points.map(p => (p, distanceFuncSet(p)))

    val (topRightSet, bottomLeftSet) = pointsWithDistanceSet.partition(_._2 < 0)

    val hull = (
      leftPoint :: findLocalHull(topRightSet, leftPoint, rightPoint)
      ) ::: (
      rightPoint :: findLocalHull(bottomLeftSet, rightPoint, leftPoint)
      )

    // close the loop
    hull :+ hull.head

  }

}
