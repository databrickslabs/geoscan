#
# Copyright 2021 Databricks, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from pyspark.ml.param.shared import *
from pyspark import keyword_only
from pyspark.ml.util import JavaMLReadable, JavaMLWritable, MLReader, _jvm
from pyspark.ml.wrapper import JavaEstimator, JavaModel, JavaParams


class GeoscanMLReader(MLReader):

    """
    (Custom) Specialization of :py:class:`MLReader` for :py:class:`JavaParams` types
    KUDOS to: https://raufer.github.io/2018/02/08/custom-spark-models-with-python-wrappers/
    """

    def __init__(self, clazz, java_class):
        self._clazz = clazz
        self._jread = self._load_java_obj(java_class).read()

    def load(self, path):
        """Load the ML instance from the input path."""
        java_obj = self._jread.load(path)
        return self._clazz._from_java(java_obj)

    @classmethod
    def _load_java_obj(cls, java_class):
        """Load the peer Java object of the ML instance."""
        java_obj = _jvm()
        for name in java_class.split("."):
            java_obj = getattr(java_obj, name)
        return java_obj


class _GeoscanParams:

    epsilon = Param(
        Params._dummy(), "epsilon", "The maximum distance (in meters) 2 points must be separated",
        typeConverter=TypeConverters.toInt)

    minPts = Param(
        Params._dummy(), "minPts", "The minimum number of K neighbours in an epsilon range",
        typeConverter=TypeConverters.toInt)

    latitudeCol = Param(
        Params._dummy(), "latitudeCol", "The latitude column in the input dataframe",
        typeConverter=TypeConverters.toString)

    longitudeCol = Param(
        Params._dummy(), "longitudeCol", "The longitude column in the input dataframe",
        typeConverter=TypeConverters.toString)

    predictionCol = Param(
        Params._dummy(), "predictionCol", "The prediction column in the output dataframe",
        typeConverter=TypeConverters.toString)

    groupedCol = Param(
        Params._dummy(), "groupedCol", "The column to group dataframe against",
        typeConverter=TypeConverters.toString)

    def __init__(self, *args):
        super(_GeoscanParams, self).__init__(*args)
        self._setDefault(
            epsilon=500,
            minPts=3,
            predictionCol="predicted",
            latitudeCol="latitude",
            longitudeCol="longitude",
            groupedCol="user"
        )

    def getEpsilon(self):
        return self.getOrDefault(self.epsilon)

    def getMinPts(self):
        return self.getOrDefault(self.minPts)

    def getLatitudeCol(self):
        return self.getOrDefault(self.latitudeCol)

    def getLongitudeCol(self):
        return self.getOrDefault(self.longitudeCol)

    def getPredictionCol(self):
        return self.getOrDefault(self.predictionCol)

    def getGroupedCol(self):
        return self.getOrDefault(self.groupedCol)


class Geoscan(JavaEstimator, _GeoscanParams, JavaMLReadable, JavaMLWritable):

    _classpath = 'com.databricks.labs.gis.ml.Geoscan'

    @keyword_only
    def __init__(self, *,
                 epsilon=500,
                 minPts=3,
                 predictionCol="predicted",
                 latitudeCol="latitude",
                 longitudeCol="longitude"
                 ):

        super(Geoscan, self).__init__()
        self._java_obj = self._new_java_obj(Geoscan._classpath, self.uid)
        kwargs = self._input_kwargs
        self.setParams(**kwargs)

    @keyword_only
    def setParams(self, *,
                  epsilon=500,
                  minPts=3,
                  predictionCol="predicted",
                  latitudeCol="latitude",
                  longitudeCol="longitude"):

        kwargs = self._input_kwargs
        return self._set(**kwargs)

    def setEpsilon(self, value):
        return self._set(epsilon=value)

    def setMinPts(self, value):
        return self._set(minPts=value)

    def setLatitudeCol(self, value):
        return self._set(latitudeCol=value)

    def setLongitudeCol(self, value):
        return self._set(longitudeCol=value)

    def setPredictionCol(self, value):
        return self._set(predictionCol=value)

    def _create_model(self, java_model):
        return GeoscanModel(java_model)


class GeoscanPersonalized(JavaEstimator, _GeoscanParams, JavaMLReadable, JavaMLWritable):

    _classpath = 'com.databricks.labs.gis.ml.GeoscanPersonalized'

    @keyword_only
    def __init__(self, *,
                 epsilon=500,
                 minPts=3,
                 predictionCol="predicted",
                 latitudeCol="latitude",
                 longitudeCol="longitude",
                 groupedCol="user",
                 ):

        super(GeoscanPersonalized, self).__init__()
        self._java_obj = self._new_java_obj(GeoscanPersonalized._classpath, self.uid)
        kwargs = self._input_kwargs
        self.setParams(**kwargs)

    @keyword_only
    def setParams(self, *,
                  epsilon=500,
                  minPts=3,
                  predictionCol="predicted",
                  latitudeCol="latitude",
                  longitudeCol="longitude",
                  groupedCol="user"):

        kwargs = self._input_kwargs
        return self._set(**kwargs)

    def setEpsilon(self, value):
        return self._set(epsilon=value)

    def setMinPts(self, value):
        return self._set(minPts=value)

    def setLatitudeCol(self, value):
        return self._set(latitudeCol=value)

    def setLongitudeCol(self, value):
        return self._set(longitudeCol=value)

    def setPredictionCol(self, value):
        return self._set(predictionCol=value)

    def setGroupedCol(self, value):
        return self._set(groupedCol=value)

    def _create_model(self, java_model):
        return GeoscanPersonalizedModel(java_model)


class GeoscanModel(JavaModel, _GeoscanParams, JavaMLReadable, JavaMLWritable):

    _classpath_model = 'com.databricks.labs.gis.ml.GeoscanModel'

    def setLatitudeCol(self, value):
        return self._set(latitudeCol=value)

    def setLongitudeCol(self, value):
        return self._set(longitudeCol=value)

    def setPredictionCol(self, value):
        return self._set(predictionCol=value)

    def toGeoJson(self):
        return self._call_java("toGeoJson")

    def getPrecision(self):
        return self._call_java("getPrecision")

    def getTiles(self, precision, layers):
        return self._call_java("getTiles", precision, layers)

    @staticmethod
    def _from_java(java_stage):
        # Generate a default new instance from the stage_name class.
        py_type = GeoscanModel
        if issubclass(py_type, JavaParams):
            # Load information from java_stage to the instance.
            py_stage = py_type()
            py_stage._java_obj = java_stage
            py_stage._resetUid(java_stage.uid())
            py_stage._transfer_params_from_java()

        return py_stage

    @classmethod
    def read(cls):
        return GeoscanMLReader(cls, cls._classpath_model)


class GeoscanPersonalizedModel(JavaModel, _GeoscanParams, JavaMLReadable, JavaMLWritable):

    _classpath_model = 'com.databricks.labs.gis.ml.GeoscanPersonalizedModel'

    def setLatitudeCol(self, value):
        return self._set(latitudeCol=value)

    def setLongitudeCol(self, value):
        return self._set(longitudeCol=value)

    def setPredictionCol(self, value):
        return self._set(predictionCol=value)

    def getPrecision(self):
        return self._call_java("getPrecision")

    def toGeoJson(self):
        return self._call_java("toGeoJson")

    def getTiles(self, precision, layers):
        return self._call_java("getTiles", precision, layers)

    @staticmethod
    def _from_java(java_stage):
        # Generate a default new instance from the stage_name class.
        py_type = GeoscanPersonalizedModel
        if issubclass(py_type, JavaParams):
            # Load information from java_stage to the instance.
            py_stage = py_type()
            py_stage._java_obj = java_stage
            py_stage._resetUid(java_stage.uid())
            py_stage._transfer_params_from_java()

        return py_stage

    @classmethod
    def read(cls):
        return GeoscanMLReader(cls, cls._classpath_model)