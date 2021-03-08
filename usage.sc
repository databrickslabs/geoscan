import com.databricks.labs.gis.ml.{Geoscan, GeoscanModel}

val geoscan = new Geoscan()
  .setLatitudeCol("latitude")
  .setLongitudeCol("longitude")
  .setPredictionCol("cluster")
  .setEpsilon(100)
  .setMinPts(3)

val distributedModel = geoscan.fit(points)

distributedModel.save("/tmp/geoscan")
val model = GeoscanModel.load("/tmp/geoscan")
model.transform(points).show()