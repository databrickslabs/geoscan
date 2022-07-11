import unittest

from pyspark.sql import SparkSession
from pyspark.sql.types import *
from pyspark.sql.utils import IllegalArgumentException
import pandas as pd
from geoscan.geoscan import *
import json
from pathlib import Path
import glob


class GeoscanTest(unittest.TestCase):

    def setUp(self):

        # this test will only work after a mvn package shaded as UBER jar
        path = Path(".")
        target_dir = Path(path.parent.absolute().parent.absolute(), "target")
        target_jar = glob.glob(str(target_dir) + '/*.jar')
        target_jar = ':'.join(target_jar)
        self.target_jar = target_jar
        print(target_jar)

        # inject scala classes
        self.spark = (
            SparkSession.builder.appName("GEOSCAN")
                .config("spark.driver.extraClassPath", target_jar)
                .master("local")
                .getOrCreate()
        )

    def tearDown(self) -> None:
        self.spark.stop()

    def test_signature(self):

        print(self.target_jar)
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
        geo_pdf = pd.read_csv('data/nyc.csv', names=['latitude', 'longitude', 'amount', 'user']).head(500)
        geo_df = self.spark.createDataFrame(geo_pdf)
        model = Geoscan().setLatitudeCol("latitude").setLongitudeCol("longitude").setPredictionCol("cluster").setEpsilon(100).setMinPts(3).fit(geo_df)
        geojson = model.toGeoJson()
        num_clusters = len(json.loads(geojson))
        self.assertEqual(2, num_clusters)

    def test_personalized(self):
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