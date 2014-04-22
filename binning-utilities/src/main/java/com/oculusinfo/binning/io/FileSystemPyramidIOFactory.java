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
package com.oculusinfo.binning.io;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oculusinfo.binning.io.impl.FileSystemPyramidIO;
import com.oculusinfo.factory.ConfigurableFactory;
import com.oculusinfo.factory.properties.StringProperty;


public class FileSystemPyramidIOFactory extends ConfigurableFactory<PyramidIO> {
	private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemPyramidIOFactory.class);

	
	public static StringProperty ROOT_PATH              = new StringProperty("root.path",
			"Unused with type=\"hbase\".  Indicates the root path of the tile pyramid - either a directory (if \"file-system\"), a package name (if \"resource\"), the full path to a .zip file (if \"zip\"), the database path (if \"sqlite\"), or the URL of the database (if \"jdbc\").  There is no default for this property.",
			null);
	public static StringProperty EXTENSION              = new StringProperty("extension",
			"Used with type=\"file-system\", \"resource\", or \"zip\".  The file extension which the serializer should expect to find on individual tiles.",
			"avro");
	
	public FileSystemPyramidIOFactory(String factoryName, ConfigurableFactory<?> parent, List<String> path) {
		super(factoryName, PyramidIO.class, parent, path);
		
		addProperty(ROOT_PATH);
		addProperty(EXTENSION);
	}

	@Override
	protected PyramidIO create() {
		try {
			String rootPath = getPropertyValue(ROOT_PATH);
			String extension = getPropertyValue(EXTENSION);
			return new FileSystemPyramidIO(rootPath, extension);
		}
		catch (Exception e) {
			LOGGER.error("Error trying to create FileSystemPyramidIO", e);
		}
		return null;
	}
	

}
