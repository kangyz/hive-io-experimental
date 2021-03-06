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

package com.facebook.giraph.hive.input;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import com.facebook.giraph.hive.common.HadoopUtils;
import com.facebook.giraph.hive.common.HiveMetastores;
import com.facebook.giraph.hive.common.HiveTableName;
import com.facebook.giraph.hive.common.HiveUtils;
import com.facebook.giraph.hive.common.Writables;
import com.facebook.giraph.hive.input.parser.Parsers;
import com.facebook.giraph.hive.input.parser.RecordParser;
import com.facebook.giraph.hive.record.HiveReadableRecord;
import com.facebook.giraph.hive.schema.HiveTableSchema;
import com.facebook.giraph.hive.schema.HiveTableSchemaImpl;
import com.facebook.giraph.hive.schema.HiveTableSchemas;
import com.google.common.collect.DiscreteDomains;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;

import java.io.IOException;
import java.util.List;

import static com.google.common.collect.Lists.transform;

/**
 * InputFormat to read from Hive
 */
public class HiveApiInputFormat
    extends InputFormat<WritableComparable, HiveReadableRecord> {
  /** Logger */
  public static final Logger LOG = Logger.getLogger(HiveApiInputFormat.class);

  /** Default profile ID if none given */
  public static final String DEFAULT_PROFILE_ID = "input-profile";

  /** Which profile to lookup */
  private String myProfileId = DEFAULT_PROFILE_ID;

  /** Input observer */
  private HiveApiInputObserver observer;

  /**
   * Get profile id
   * @return integer profile id
   */
  public String getMyProfileId() {
    return myProfileId;
  }

  /**
   * Set profile ID
   * @param myProfileId integer profile id
   */
  public void setMyProfileId(String myProfileId) {
    this.myProfileId = myProfileId;
  }

  /**
   * Get observer currently registered
   * @return Observer
   */
  public HiveApiInputObserver getObserver() {
    return observer;
  }

  /**
   * Set observer that will watch operations
   * @param observer Observer to set
   */
  public void setObserver(HiveApiInputObserver observer) {
    this.observer = observer;
  }

  /**
   * Get table schema for this profile
   * @param conf Configuration to use
   * @return HiveTableSchema
   */
  public HiveTableSchema getTableSchema(Configuration conf) {
    return HiveTableSchemas.get(conf, myProfileId);
  }

  private static String profileConfKey(String profileId) {
    return "hive.input." + profileId;
  }

  public static void setProfileInputDesc(Configuration conf,
    HiveInputDescription inputDesc, String profileId) {
    conf.set(profileConfKey(profileId), Writables.writeToEncodedStr(inputDesc));
  }

  private HiveInputDescription readProfileInputDesc(HiveConf conf) {
    HiveInputDescription inputDesc = new HiveInputDescription();
    Writables.readFieldsFromEncodedStr(conf.get(profileConfKey(myProfileId)), inputDesc);
    return inputDesc;
  }

  @Override
  public List<InputSplit> getSplits(JobContext jobContext)
    throws IOException, InterruptedException
  {
    LOG.info("getSplits for profile " + myProfileId);

    HiveConf conf = new HiveConf(jobContext.getConfiguration(), HiveApiInputFormat.class);

    ThriftHiveMetastore.Iface client;
    try {
      client = HiveMetastores.create(conf);
    } catch (TException e) {
      throw new IOException(e);
    }

    return getSplits(conf, client);
  }

  public List<InputSplit> getSplits(HiveConf conf, ThriftHiveMetastore.Iface client)
      throws IOException {
    HiveInputDescription inputDesc = readProfileInputDesc(conf);

    Table table;
    try {
      table = client.get_table(inputDesc.getDbName(), inputDesc.getTableName());
    } catch (Exception e) {
      throw new IOException(e);
    }

    final HiveTableSchema tableSchema = HiveTableSchemaImpl.fromTable(table);
    HiveTableSchemas.put(conf, myProfileId, tableSchema);

    List<InputPartition> partitions = computePartitions(inputDesc, client, table);

    List<InputSplit> splits = computeSplits(conf, inputDesc, tableSchema, partitions);

    return splits;
  }

  private List<InputSplit> computeSplits(HiveConf conf, HiveInputDescription inputDesc,
    HiveTableSchema tableSchema, List<InputPartition> partitions) throws IOException
  {
    int partitionNum = 0;
    List<InputSplit> splits = Lists.newArrayList();

    int[] columnIds = computeColumnIds(inputDesc.getColumns(), tableSchema);

    for (InputPartition inputPartition : partitions) {
      org.apache.hadoop.mapred.InputFormat baseInputFormat = inputPartition.makeInputFormat(conf);
      HadoopUtils.setInputDir(conf, inputPartition.getLocation());

      org.apache.hadoop.mapred.InputSplit[] baseSplits =
          baseInputFormat.getSplits(new JobConf(conf), inputDesc.getNumSplits());
      LOG.info("Requested " + inputDesc.getNumSplits() + " splits from partition (" +
          partitionNum + " out of " + Iterables.size(partitions) +
          ") values: " + inputPartition.getInputSplitData().getPartitionValues() +
          ", got " + baseSplits.length + " splits from inputFormat " +
          baseInputFormat.getClass().getCanonicalName());

      for (org.apache.hadoop.mapred.InputSplit baseSplit : baseSplits)  {
        InputSplit split = new HInputSplit(baseInputFormat, baseSplit,
            tableSchema, columnIds, inputPartition.getInputSplitData(), conf);
        splits.add(split);
      }

      partitionNum++;
    }
    return splits;
  }

  private int[] computeColumnIds(List<String> columnNames, HiveTableSchema tableSchema) {
    List<Integer> ints;
    if (columnNames.isEmpty()) {
      Range<Integer> range = Ranges.closedOpen(0, tableSchema.numColumns());
      ints = range.asSet(DiscreteDomains.integers()).asList();
    } else {
      ints = transform(columnNames, HiveTableSchemas
          .schemaLookupFunc(tableSchema));
    }
    int[] result = new int[ints.size()];
    for (int i = 0; i < ints.size(); ++i) {
      result[i] = ints.get(i);
    }
    return result;
  }

  private List<InputPartition> computePartitions(HiveInputDescription inputDesc,
    ThriftHiveMetastore.Iface client, Table table) throws IOException
  {
    List<InputPartition> partitions = Lists.newArrayList();

    if (table.getPartitionKeysSize() == 0) {
      // table without partitions
      partitions.add(InputPartition.newFromHiveTable(table));
    } else {
      // table with partitions, find matches to user filter.
      List<Partition> hivePartitions;
      try {
        hivePartitions = client.get_partitions_by_filter(inputDesc.getDbName(),
            inputDesc.getTableName(), inputDesc.getPartitionFilter(), (short) -1);
      } catch (Exception e) {
        throw new IOException(e);
      }
      for (Partition hivePartition : hivePartitions) {
        partitions.add(InputPartition.newFromHivePartition(hivePartition));
      }
    }
    return partitions;
  }

  @Override
  public RecordReaderImpl
  createRecordReader(InputSplit inputSplit, TaskAttemptContext context)
    throws IOException, InterruptedException
  {
    Configuration conf = context.getConfiguration();
    JobConf jobConf = new JobConf(conf);

    HInputSplit split = (HInputSplit) inputSplit;
    split.setConf(jobConf);

    int[] columnIds = split.getColumnIds();
    HiveUtils.setReadColumnIds(jobConf, columnIds);

    // CHECKSTYLE: stop LineLength
    org.apache.hadoop.mapred.RecordReader<WritableComparable, Writable> baseRecordReader =
        split.getBaseRecordReader(jobConf, context);
    // CHECKSTYLE: resume LineLength

    RecordParser<Writable> recordParser = getParser(baseRecordReader.createValue(),
        split.getTableSchema().getTableName(), split, columnIds);

    RecordReaderImpl reader = new RecordReaderImpl(baseRecordReader, recordParser);
    reader.setObserver(observer);

    return reader;
  }

  private RecordParser<Writable> getParser(Writable exampleValue,
    HiveTableName tableName, HInputSplit split, int[] columnIds)
  {
    Deserializer deserializer = split.getDeserializer();
    String[] partitionValues = split.getPartitionValues();
    int numColumns = split.getTableSchema().numColumns();
    return Parsers.bestParser(deserializer, numColumns, columnIds, tableName,
        partitionValues, exampleValue);
  }
}
