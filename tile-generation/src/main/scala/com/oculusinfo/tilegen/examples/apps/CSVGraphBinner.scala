/*
 * Copyright (c) 2014 Oculus Info Inc.
 * http://www.oculusinfo.com/
 *
 * Released under the MIT License.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.oculusinfo.tilegen.examples.apps



import java.io.FileInputStream
import java.util.Properties
import scala.collection.JavaConverters._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import com.oculusinfo.tilegen.spark.SparkConnector
import com.oculusinfo.tilegen.spark.GeneralSparkConnector
import com.oculusinfo.tilegen.datasets.Dataset
import com.oculusinfo.tilegen.datasets.DatasetFactory
import com.oculusinfo.tilegen.tiling.CartesianIndexScheme
import com.oculusinfo.tilegen.tiling.RDDBinner
import com.oculusinfo.tilegen.tiling.RDDLineBinner
import com.oculusinfo.tilegen.tiling.HBaseTileIO
import com.oculusinfo.tilegen.tiling.LocalTileIO
import com.oculusinfo.tilegen.util.PropertiesWrapper
import com.oculusinfo.binning.io.PyramidIO
import com.oculusinfo.tilegen.tiling.TileIO
import com.oculusinfo.tilegen.tiling.SqliteTileIO
import com.oculusinfo.tilegen.datasets.CSVIndexExtractor
import com.oculusinfo.tilegen.datasets.CSVDatasetBase
import com.oculusinfo.tilegen.datasets.CartesianIndexExtractor
import org.apache.spark.SparkContext
import com.oculusinfo.tilegen.datasets.CSVRecordPropertiesWrapper
import com.oculusinfo.tilegen.datasets.CSVDataset
import com.oculusinfo.tilegen.datasets.LineSegmentIndexExtractor
import com.oculusinfo.tilegen.datasets.StaticProcessingStrategy
import org.apache.spark.storage.StorageLevel
import com.oculusinfo.tilegen.datasets.GraphRecordParser
import com.oculusinfo.tilegen.datasets.CSVDataSource
import com.oculusinfo.tilegen.datasets.CSVFieldExtractor
import scala.util.Try
import org.apache.spark.api.java.JavaRDD


/*
 * The following properties control how the application runs:
 * 
 *  hbase.zookeeper.quorum
 *      If tiles are written to hbase, the zookeeper quorum location needed to
 *      connect to hbase.
 * 
 *  hbase.zookeeper.port
 *      If tiles are written to hbase, the port through which to connect to
 *      zookeeper.  Defaults to 2181
 * 
 *  hbase.master
 *      If tiles are written to hbase, the location of the hbase master to
 *      which to write them
 *
 * 
 *  spark
 *      The location of the spark master.
 *      Defaults to "localhost"
 *
 *  sparkhome
 *      The file system location of Spark in the remote location (and,
 *      necessarily, on the local machine too)
 *      Defaults to "/srv/software/spark-0.7.2"
 * 
 *  user
 *      A user name to stick in the job title so people know who is running the
 *      job
 *
 *
 * 
 *  oculus.tileio.type
 *      The way in which tiles are written - either hbase (to write to hbase,
 *      see hbase. properties above to specify where) or file  to write to the
 *      local file system
 *      Default is hbase
 *
 */


class CSVGraphProcessingStrategy[IT: ClassManifest] (sc: SparkContext,
	                                   cacheRaw: Boolean,
	                                   cacheFilterable: Boolean,
	                                   cacheProcessed: Boolean,
	                                   properties: CSVRecordPropertiesWrapper,
	                                   indexer: CSVIndexExtractor[IT])
			extends StaticProcessingStrategy[IT, Double](sc) {
	
	// This is a weird initialization problem that requires some
	// documentation to explain.
	// What we really want here is for rawData to be initialized in the
	// getData method, below.  However, this method is called from
	// StaticProcessingStrategy.rdd, which is called during the our
	// parent's <init> call - which happens before we event get to this
	// line.  So when we get here, if we assigned rawData directly in
	// getData, this line below, having to assign rawData some value,
	// would overwrite it.
	// We could just say rawData = rawData (that does work, I checked),
	// but that seemed semantically too confusing to abide. So instead,
	// getData sets rawData2, which can the be assigned to rawData before
	// it gets written in its own initialization (since initialization
	// lines are run in order).
	private var rawData: RDD[String] = rawData2
	private var rawData2: RDD[String] = null

	private lazy val filterableData: RDD[(String, List[Double])] = {
		val localProperties = properties
		val data = rawData.mapPartitions(iter =>
			{
				val parser = new GraphRecordParser(localProperties)
				// Parse the records from the raw data, parsing all fields
				// The funny end syntax tells scala to treat fields as a varargs
				parser.parseGraphRecords(iter, localProperties.fields:_*)
					.filter(_._2.isSuccess).map{case (record, fields) => (record, fields.get)}
			}
		)
		if (cacheFilterable)
			data.persist(StorageLevel.MEMORY_AND_DISK)
		data
	}

	def getRawData = rawData
	def getFilterableData = filterableData

	def getData: RDD[(IT, Double)] = {
		val localProperties = properties
		val localIndexer = indexer
		val localZVar = properties.getString("oculus.binning.valueField",
											"The field to use for the value to tile",
											Some("count"))
		rawData2 = {
			val source = new CSVDataSource(properties)
			val data = source.getData(sc);
			if (cacheRaw)
				data.persist(StorageLevel.MEMORY_AND_DISK)
			data
		}

		val data = rawData2.mapPartitions(iter =>
			{
				val parser = new GraphRecordParser(localProperties)
				// Determine which fields we need
				val fields = if ("count" == localZVar) {
					localIndexer.fields
				} else {
					localIndexer.fields :+ localZVar
				}

				// Parse the records from the raw data
				parser.parseGraphRecords(iter, fields:_*)
					.map(_._2) // We don't need the original record (in _1)
			}
		).filter(r =>
			// Filter out unsuccessful parsings
			r.isSuccess
		).map(_.get).mapPartitions(iter =>
			{
				val extractor = new CSVFieldExtractor(localProperties)

				iter.map(t =>
					{
						// Determine our index value
						val indexValue = Try(
							{
								val indexFields = localIndexer.fields
								val fieldValues = indexFields.map(field =>
									(field -> extractor.getFieldValue(field)(t))
								).map{case (k, v) => (k, v.get)}.toMap
								localIndexer.calculateIndex(fieldValues)
							}
						)

						// Determine and add in our binnable value
						(indexValue,
						 extractor.getFieldValue(localZVar)(t))
					}
				)
			}
		).filter(record =>
			record._1.isSuccess && record._2.isSuccess
		).map(record =>
			(record._1.get, record._2.get)
		)

		if (cacheProcessed)
			data.persist(StorageLevel.MEMORY_AND_DISK)

		data
	}
}


/**
 * Handles basic RDD's using a ProcessingStrategy. 
 */
class CSVGraphDataset[IT: ClassManifest](indexer:  CSVIndexExtractor[IT],
                                     properties: CSVRecordPropertiesWrapper,
                                     tileWidth: Int,
                                     tileHeight: Int)
		extends CSVDatasetBase[IT](indexer, properties, tileWidth, tileHeight) {
	// Just some Filter type aliases from Queries.scala
	import com.oculusinfo.tilegen.datasets.FilterAware._

	type STRATEGY_TYPE = CSVGraphProcessingStrategy[IT]
	protected var strategy: STRATEGY_TYPE = null

	def getRawData: RDD[String] = strategy.getRawData

	def getRawFilteredData (filterFcn: Filter):	RDD[String] = {
		strategy.getFilterableData
			.filter{ case (record, fields) => filterFcn(fields)}
			.map(_._1)
	}
	def getRawFilteredJavaData(filterFcn: Filter): JavaRDD[String] =
		JavaRDD.fromRDD(getRawFilteredData(filterFcn))

	def getFieldFilterFunction (field: String, min: Double, max: Double): Filter = {
		val localProperties = properties
		new FilterFunction with Serializable {
			def apply (valueList: List[Double]): Boolean = {
				val index = localProperties.fieldIndices(field)
				val value = valueList(index)
				min <= value && value <= max
			}
			override def toString: String = "%s Range[%.4f, %.4f]".format(field, min, max)
		}
	}

	def initialize (sc: SparkContext,
	                cacheRaw: Boolean,
	                cacheFilterable: Boolean,
	                cacheProcessed: Boolean): Unit =
		initialize(new CSVGraphProcessingStrategy[IT](sc, cacheRaw, cacheFilterable, cacheProcessed, properties, indexer))	
}



object CSVGraphBinner {
	
	private var _graphDataType = "nodes"
	
	def getTileIO(properties: PropertiesWrapper): TileIO = {
		properties.getString("oculus.tileio.type",
		                     "Where to put tiles",
		                     Some("hbase")) match {
			case "hbase" => {
				val quorum = properties.getStringOption("hbase.zookeeper.quorum",
				                                        "The HBase zookeeper quorum").get
				val port = properties.getString("hbase.zookeeper.port",
				                                "The HBase zookeeper port",
				                                Some("2181"))
				val master = properties.getStringOption("hbase.master",
				                                        "The HBase master").get
				new HBaseTileIO(quorum, port, master)
			}
			case "sqlite" => {
				val path =
					properties.getString("oculus.tileio.sqlite.path",
					                     "The path to the database",
					                     Some(""))
				new SqliteTileIO(path)
				
			}
			case _ => {
				val extension =
					properties.getString("oculus.tileio.file.extension",
					                     "The extension with which to write tiles",
					                     Some("avro"))
				new LocalTileIO(extension)
			}
		}
	}

	def createIndexExtractor (properties: PropertiesWrapper): CSVIndexExtractor[_] = {
		_graphDataType = properties.getString("oculus.binning.graph.data",
		                                     "The type of graph data to tile (nodes or edges). "+
			                                     "Default is nodes.",
		                                     Some("nodes"))
		                                     
		// NOTE!  currently, indexType is assumed to be cartesian for graph data
//		val indexType = properties.getString("oculus.binning.index.type",
//		                                     "The type of index to use in the data.  Currently "+
//			                                     "suppoted options are cartesian (the default) "+
//			                                     "and ipv4.",
//		                                     Some("cartesian"))
			
		_graphDataType match {
			case "nodes" => {
				val xVar = properties.getString("oculus.binning.xField",
				                                "The field to use for the X axis of tiles produced",
				                                Some(CSVDatasetBase.ZERO_STR))
				val yVar = properties.getString("oculus.binning.yField",
				                                "The field to use for the Y axis of tiles produced",
				                                Some(CSVDatasetBase.ZERO_STR))
				new CartesianIndexExtractor(xVar, yVar)
			}
			case "edges" => {
				// edges require two cartesian endpoints 
				val xVar1 = properties.getString("oculus.binning.xField",
				                                "The field to use for the X axis for edge start pt",
				                                Some(CSVDatasetBase.ZERO_STR))
				val yVar1 = properties.getString("oculus.binning.yField",
				                                "The field to use for the Y axis for edge start pt",
				                                Some(CSVDatasetBase.ZERO_STR))
				val xVar2 = properties.getString("oculus.binning.xField2",
				                                "The field to use for the X axis for edge end pt",
				                                Some(CSVDatasetBase.ZERO_STR))
				val yVar2 = properties.getString("oculus.binning.yField2",
				                                "The field to use for the Y axis for edge end pt",
				                                Some(CSVDatasetBase.ZERO_STR))				                                
				new LineSegmentIndexExtractor(xVar1, yVar1, xVar2, yVar2)
			}			

		}			
	}	
	
	def processDataset[IT: ClassManifest,
	                   PT: ClassManifest, 
	                   BT] (dataset: Dataset[IT, PT, BT],
	                        tileIO: TileIO): Unit = {

		if (_graphDataType == "edges") {
			val binner = new RDDLineBinner
			binner.debug = true
			dataset.getLevels.map(levels =>
				{
					val procFcn: RDD[(IT, PT)] => Unit =
						rdd =>
					{
						val tiles = binner.processDataByLevel(rdd,
						                                      dataset.getIndexScheme,
						                                      dataset.getBinDescriptor,
						                                      dataset.getTilePyramid,
						                                      levels,
						                                      (dataset.getNumXBins max dataset.getNumYBins),
						                                      dataset.getConsolidationPartitions,
						                                      dataset.isDensityStrip)
						tileIO.writeTileSet(dataset.getTilePyramid,
						                    dataset.getName,
						                    tiles,
						                    dataset.getBinDescriptor,
						                    dataset.getName,
						                    dataset.getDescription)
					}
					dataset.process(procFcn, None)
				}
			)
		}
		else  {//if (_graphDataType == "nodes")
			val binner = new RDDBinner
			binner.debug = true
			dataset.getLevels.map(levels =>
				{
					val procFcn: RDD[(IT, PT)] => Unit =
						rdd =>
					{
						val tiles = binner.processDataByLevel(rdd,
						                                      dataset.getIndexScheme,
						                                      dataset.getBinDescriptor,
						                                      dataset.getTilePyramid,
						                                      levels,
						                                      (dataset.getNumXBins max dataset.getNumYBins),
						                                      dataset.getConsolidationPartitions,
						                                      dataset.isDensityStrip)
						tileIO.writeTileSet(dataset.getTilePyramid,
						                    dataset.getName,
						                    tiles,
						                    dataset.getBinDescriptor,
						                    dataset.getName,
						                    dataset.getDescription)
					}
					dataset.process(procFcn, None)
				}
			)
		}
	}

	/**
	 * This function is simply for pulling out the generic params from the DatasetFactory,
	 * so that they can be used as params for other types.
	 */
	def processDatasetGeneric[IT, PT, BT] (dataset: Dataset[IT, PT, BT],
	                                       tileIO: TileIO): Unit =
		processDataset(dataset, tileIO)(dataset.indexTypeManifest, dataset.binTypeManifest)

		
	private def getDataset[T: ClassManifest] (indexer: CSVIndexExtractor[T],
	                                          properties: CSVRecordPropertiesWrapper,
	                                          tileWidth: Int,
	                                          tileHeight: Int): CSVGraphDataset[T] =
		new CSVGraphDataset(indexer, properties, tileWidth, tileHeight)		

	
	private def getDatasetGeneric[T] (indexer: CSVIndexExtractor[T],
	                                  properties: CSVRecordPropertiesWrapper,
	                                  tileWidth: Int,
	                                  tileHeight: Int): CSVGraphDataset[T] =
		getDataset(indexer, properties, tileWidth, tileHeight)(indexer.indexTypeManifest)		

		
	def createDataset(sc: SparkContext,
	                   dataDescription: Properties,
	                   cacheRaw: Boolean,
	                   cacheFilterable: Boolean,
	                   cacheProcessed: Boolean,
	                   tileWidth: Int = 256,
	                   tileHeight: Int = 256): Dataset[_, _, _] = {
		// Wrap parameters more usefully
		val properties = new CSVRecordPropertiesWrapper(dataDescription)

		// Determine indexing information
		val indexer = createIndexExtractor(properties)

		//val dataset:CSVDataset[_] = new CSVDataset(indexer, properties, tileWidth, tileHeight)  // getDatasetGeneric(indexer, properties, tileWidth, tileHeight)
		//val dataset:CSVTimeRangeDataset[_] = getDatasetGeneric(indexer, properties, tileWidth, tileHeight)
		val dataset:CSVGraphDataset[_] = getDatasetGeneric(indexer, properties, tileWidth, tileHeight)
		dataset.initialize(sc, cacheRaw, cacheFilterable, cacheProcessed)
		dataset
	}
	
	def main (args: Array[String]): Unit = {
		if (args.size<1) {
			println("Usage:")
			println("\tCSVGraphBinner [-d default_properties_file] job_properties_file_1 job_properties_file_2 ...")
			System.exit(1)
		}

		// Read default properties
		var argIdx = 0
		var defProps = new Properties()

		while ("-d" == args(argIdx)) {
			argIdx = argIdx + 1
			val stream = new FileInputStream(args(argIdx))
			defProps.load(stream)
			stream.close()
			argIdx = argIdx + 1
		}
		val defaultProperties = new PropertiesWrapper(defProps)
		val connector = defaultProperties.getSparkConnector()
		val sc = connector.getSparkContext("Pyramid Binning")
		val tileIO = getTileIO(defaultProperties)

		// Run for each real properties file
		val startTime = System.currentTimeMillis()
		while (argIdx < args.size) {
			val fileStartTime = System.currentTimeMillis()
			val props = new Properties(defProps)
			val propStream = new FileInputStream(args(argIdx))
			props.load(propStream)
			propStream.close()

			processDatasetGeneric(createDataset(sc, props, false, false, true), tileIO)

			val fileEndTime = System.currentTimeMillis()
			println("Finished binning "+args(argIdx)+" in "+((fileEndTime-fileStartTime)/60000.0)+" minutes")

			argIdx = argIdx + 1
		}
		val endTime = System.currentTimeMillis()
		println("Finished binning all sets in "+((endTime-startTime)/60000.0)+" minutes")
	}
}