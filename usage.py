#
# %pip install /dbfs/FileStore/antoine.amend@databricks.com/geoscan/python
#

import pandas as pd
from pyspark.sql import functions as F
geo_pdf = pd.read_csv('nyc_shapes.csv', names=['user', 'latitude', 'longitude', 'amount']).head(500)
geo_df = spark.createDataFrame(geo_pdf)
geo_df.show()

from geoscan import Geoscan, GeoscanModel
model = Geoscan().setLatitudeCol("latitude").setLongitudeCol("longitude").setPredictionCol("cluster").setEpsilon(100).setMinPts(3).fit(geo_df)
model.toGeoJson()
model.getPrecision()
model.getTiles(10).show()

model.save('/tmp/geoscan_model/distributed')
model = GeoscanModel.load('/tmp/geoscan_model/distributed')
model.transform(geo_df).show()
model.anomalies(geo_df).filter(F.col("cluster") == 0).show()
model.anomalies(geo_df).filter(F.col("cluster") != 0).show()

from geoscan import GeoscanPersonalized, GeoscanPersonalizedModel
model = GeoscanPersonalized().setLatitudeCol("latitude").setLongitudeCol("longitude").setPredictionCol("cluster").setEpsilon(100).setMinPts(3).setGroupedCol("user").fit(geo_df)
model.toGeoJson().show()
model.getPrecision()
model.getTiles(10).show()

model.save('/tmp/geoscan_model/personalized')
model = GeoscanPersonalizedModel.load('/tmp/geoscan_model/personalized')
model.transform(geo_df).show()
model.anomalies(geo_df).filter(F.col("cluster") == 0).show()
model.anomalies(geo_df).filter(F.col("cluster") != 0).show()









