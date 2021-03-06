/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.giraph.hive.input.parser.array;

import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.io.Writable;

import com.facebook.giraph.hive.input.parser.RecordParser;
import com.facebook.giraph.hive.record.HiveReadableRecord;

import java.io.IOException;

public class ArrayParser implements RecordParser {
  private final ArrayParserData parserData;
  private final ArrayRecord record;

  public ArrayParser(String[] partitionValues, int numColumns, ArrayParserData parserData) {
    this.parserData = parserData;
    this.record = new ArrayRecord(numColumns, partitionValues, parserData.hiveTypes);
  }

  @Override
  public HiveReadableRecord createRecord() {
    return record;
  }

  @Override
  public HiveReadableRecord parse(Writable value, HiveReadableRecord record)
      throws IOException {
    ArrayRecord arrayRecord = (ArrayRecord) record;
    arrayRecord.reset();

    Object rowData;
    try {
      rowData = parserData.deserializer.deserialize(value);
    } catch (SerDeException e) {
      throw new IOException(e);
    }

    for (int i = 0; i < parserData.columnIndexes.length; ++i) {
      int columnIndex = parserData.columnIndexes[i];
      StructField structField = parserData.structFields[columnIndex];

      Object fieldData = parserData.inspector.getStructFieldData(rowData, structField);
      if (fieldData == null) {
        arrayRecord.setNull(columnIndex, true);
        continue;
      }

      if (arrayRecord.getHiveType(columnIndex).isCollection()) {
        ObjectInspector fieldInspector = structField.getFieldObjectInspector();
        Object parsed = ObjectInspectorUtils.copyToStandardJavaObject(fieldData, fieldInspector);
        arrayRecord.setObject(columnIndex, parsed);
      } else {
        parsePrimitive(arrayRecord, columnIndex, fieldData);
      }
    }

    return arrayRecord;
  }

  private void parsePrimitive(ArrayRecord arrayRecord, int columnIndex, Object fieldData) {
    PrimitiveObjectInspector fieldInspector = parserData.primitiveInspectors[columnIndex];
    Object primitiveData = fieldInspector.getPrimitiveJavaObject(fieldData);

    switch (arrayRecord.getNativeType(columnIndex)) {
      case BOOLEAN:
        arrayRecord.setBoolean(columnIndex, (Boolean) primitiveData);
        break;
      case LONG:
        arrayRecord.setLong(columnIndex, ((Number) primitiveData).longValue());
        break;
      case DOUBLE:
        arrayRecord.setDouble(columnIndex, ((Number) primitiveData).doubleValue());
        break;
      case STRING:
        arrayRecord.setString(columnIndex, (String) primitiveData);
        break;
    }
  }
}
