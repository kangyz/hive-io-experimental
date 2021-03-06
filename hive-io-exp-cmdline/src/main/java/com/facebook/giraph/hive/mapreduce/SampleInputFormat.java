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
package com.facebook.giraph.hive.mapreduce;

import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.List;

public class SampleInputFormat extends InputFormat<NullWritable, MapWritable> {
  @Override public RecordReader<NullWritable, MapWritable> createRecordReader(
      InputSplit split, TaskAttemptContext context)
      throws IOException, InterruptedException {
    SampleInputSplit hSplit = (SampleInputSplit) split;
    if (hSplit.getNum() == 0) {
      return new SplitReader(HiveTools.mapperData1);
    } else {
      return new SplitReader(HiveTools.mapperData2);
    }
  }

  @Override public List<InputSplit> getSplits(JobContext context)
      throws IOException, InterruptedException {
    return Lists.<InputSplit>newArrayList(
        new SampleInputSplit(0),
        new SampleInputSplit(1));
  }
}
