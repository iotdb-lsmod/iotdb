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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.execution.operator.process;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.queryengine.common.header.ColumnHeader;
import org.apache.iotdb.db.queryengine.common.header.ColumnHeaderConstant;
import org.apache.iotdb.db.queryengine.execution.operator.Operator;
import org.apache.iotdb.db.queryengine.execution.operator.OperatorContext;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.InputLocation;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.block.TsBlock;
import org.apache.iotdb.tsfile.read.common.block.TsBlockBuilder;
import org.apache.iotdb.tsfile.read.common.block.column.ColumnBuilder;
import org.apache.iotdb.tsfile.read.common.block.column.TimeColumnBuilder;
import org.apache.iotdb.tsfile.utils.Binary;
import org.apache.iotdb.tsfile.utils.Pair;

import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class IntoOperator extends AbstractIntoOperator {

  private final List<Pair<String, PartialPath>> sourceTargetPathPairList;

  private final List<Pair<String, String>> sourceColumnToViewList;

  @SuppressWarnings("squid:S107")
  public IntoOperator(
      OperatorContext operatorContext,
      Operator child,
      List<TSDataType> inputColumnTypes,
      Map<PartialPath, Map<String, InputLocation>> targetPathToSourceInputLocationMap,
      Map<PartialPath, Map<String, TSDataType>> targetPathToDataTypeMap,
      Map<String, Boolean> targetDeviceToAlignedMap,
      List<Pair<String, PartialPath>> sourceTargetPathPairList,
      List<Pair<String, String>> sourceColumnToViewList,
      Map<String, InputLocation> sourceColumnToInputLocationMap,
      ExecutorService intoOperationExecutor,
      long statementSizePerLine) {
    super(
        operatorContext,
        child,
        inputColumnTypes,
        sourceColumnToInputLocationMap,
        intoOperationExecutor,
        statementSizePerLine);
    this.sourceTargetPathPairList = sourceTargetPathPairList;
    this.sourceColumnToViewList = sourceColumnToViewList;
    insertTabletStatementGenerators =
        constructInsertTabletStatementGenerators(
            targetPathToSourceInputLocationMap,
            targetPathToDataTypeMap,
            targetDeviceToAlignedMap,
            typeConvertors,
            maxRowNumberInStatement);
  }

  @Override
  protected boolean processTsBlock(TsBlock inputTsBlock) {
    if (inputTsBlock == null || inputTsBlock.isEmpty()) {
      return true;
    }

    int readIndex = 0;
    while (readIndex < inputTsBlock.getPositionCount()) {
      int lastReadIndex = readIndex;
      for (InsertTabletStatementGenerator generator : insertTabletStatementGenerators) {
        lastReadIndex = Math.max(lastReadIndex, generator.processTsBlock(inputTsBlock, readIndex));
      }
      readIndex = lastReadIndex;
      if (insertMultiTabletsInternally(true)) {
        cachedTsBlock = inputTsBlock.subTsBlock(readIndex);
        return false;
      }
    }
    return true;
  }

  @Override
  protected TsBlock tryToReturnResultTsBlock() {
    if (insertMultiTabletsInternally(false)) {
      return null;
    }

    finished = true;
    return constructResultTsBlock();
  }

  private TsBlock constructResultTsBlock() {
    List<TSDataType> outputDataTypes =
        ColumnHeaderConstant.selectIntoColumnHeaders.stream()
            .map(ColumnHeader::getColumnType)
            .collect(Collectors.toList());
    TsBlockBuilder resultTsBlockBuilder = new TsBlockBuilder(outputDataTypes);
    TimeColumnBuilder timeColumnBuilder = resultTsBlockBuilder.getTimeColumnBuilder();
    ColumnBuilder[] columnBuilders = resultTsBlockBuilder.getValueColumnBuilders();
    for (int i = 0; i < sourceTargetPathPairList.size(); i++) {
      Pair<String, PartialPath> sourceTargetPathPair = sourceTargetPathPairList.get(i);
      String sourceColumn = sourceTargetPathPair.left;
      if (StringUtils.isNotEmpty(sourceColumnToViewList.get(i).right)) {
        sourceColumn = sourceColumnToViewList.get(i).right;
      }
      timeColumnBuilder.writeLong(0);
      columnBuilders[0].writeBinary(new Binary(sourceColumn));
      columnBuilders[1].writeBinary(new Binary(sourceTargetPathPair.right.toString()));
      columnBuilders[2].writeInt(
          findWritten(
              sourceTargetPathPair.right.getDevice(), sourceTargetPathPair.right.getMeasurement()));
      resultTsBlockBuilder.declarePosition();
    }
    return resultTsBlockBuilder.build();
  }
}
