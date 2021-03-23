import unittest

from pyspark.sql import SparkSession
from pyspark.sql.types import *
from pyspark.sql.utils import IllegalArgumentException
import pandas as pd
import glob, os
from geoscan import Geoscan, GeoscanModel
from geoscan import GeoscanPersonalized, GeoscanPersonalizedModel
import json

class GeoscanTest(unittest.TestCase):

    def setUp(self):

        # this test will only work after a mvn package shaded as UBER jar
        jar_file = "{}/{}-{}.jar".format(
            os.getenv('TARGET_DIR'),
            os.getenv('ARTIFACT'),
            os.getenv('VERSION'))

        # inject scala classes
        self.spark = (
            SparkSession.builder.appName("GEOSCAN")
                .config("spark.driver.extraClassPath", jar_file)
                .master("local")
                .getOrCreate()
        )

    def tearDown(self) -> None:
        self.spark.stop()

    def test_signature(self):

        print("Testing GEOSCAN signature")
        # should fail when specifying the wrong type
        with self.assertRaises(TypeError):
            Geoscan().setMinPts("HELLO")

        # should fail when specifying the wrong type
        with self.assertRaises(TypeError):
            Geoscan().setEpsilon("WORLD")

        geo_pdf = pd.read_csv('data/nyc.csv', names=['latitude', 'longitude', 'amount', 'user'])
        geo_df = self.spark.createDataFrame(geo_pdf)

        # should fail when column does not exist
        with self.assertRaises(IllegalArgumentException):
            Geoscan().setLatitudeCol("hello").fit(geo_df)

        # should fail when prediction column already exist
        with self.assertRaises(IllegalArgumentException):
            Geoscan().setPredictionCol("amount").fit(geo_df)

    def test_dataframe(self):
        print("Testing GEOSCAN dataframe")
        geo_pdf = pd.read_csv('data/nyc.csv', names=['latitude', 'longitude', 'amount', 'user']).head(500)
        geo_df = self.spark.createDataFrame(geo_pdf)
        model = Geoscan().setLatitudeCol("latitude").setLongitudeCol("longitude").setPredictionCol("cluster").setEpsilon(100).setMinPts(3).fit(geo_df)
        geojson = model.toGeoJson()
        num_clusters = len(json.loads(geojson))
        self.assertEqual(2, num_clusters)

    def test_personalized(self):
        print("Testing Personalized GEOSCAN dataframe")
        geo_pdf = pd.read_csv('data/nyc.csv', names=['latitude', 'longitude', 'amount', 'user']).head(500)
        users = geo_pdf['user'].unique().shape[0]
        geo_df = self.spark.createDataFrame(geo_pdf)
        model = GeoscanPersonalized().setGroupedCol("user").setLatitudeCol("latitude").setLongitudeCol("longitude").setPredictionCol("cluster").setEpsilon(100).setMinPts(3).fit(geo_df)
        geojson = model.toGeoJson()
        geojson.show()
        self.assertEqual(users, geojson.count())

## MAIN
if __name__ == '__main__':
    unittest.main()