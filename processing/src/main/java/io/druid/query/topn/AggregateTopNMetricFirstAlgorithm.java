/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.topn;

import com.metamx.common.ISE;
import com.metamx.common.Pair;
import io.druid.collections.StupidPool;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.AggregatorUtil;
import io.druid.query.aggregation.PostAggregator;
import io.druid.segment.Capabilities;
import io.druid.segment.Cursor;
import io.druid.segment.DimensionSelector;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 */
public class AggregateTopNMetricFirstAlgorithm implements TopNAlgorithm<int[], TopNParams>
{
  private final Capabilities capabilities;
  private final TopNQuery query;
  private final StupidPool<ByteBuffer> bufferPool;

  public AggregateTopNMetricFirstAlgorithm(
      Capabilities capabilities,
      TopNQuery query,
      StupidPool<ByteBuffer> bufferPool
  )
  {
    this.capabilities = capabilities;
    this.query = query;
    this.bufferPool = bufferPool;
  }

  @Override
  public TopNParams makeInitParams(
      DimensionSelector dimSelector, Cursor cursor
  )
  {
    return new TopNParams(
        dimSelector,
        cursor,
        Integer.MAX_VALUE
    );
  }

  @Override
  public void run(
      TopNParams params, TopNResultBuilder resultBuilder, int[] ints
  )
  {
    final String metric = query.getTopNMetricSpec().getMetricName(query.getDimensionSpec());
    Pair<List<AggregatorFactory>, List<PostAggregator>> condensedAggPostAggPair = AggregatorUtil.condensedAggregators(
        query.getAggregatorSpecs(),
        query.getPostAggregatorSpecs(),
        metric
    );

    if (condensedAggPostAggPair.lhs.isEmpty() && condensedAggPostAggPair.rhs.isEmpty()) {
      throw new ISE("WTF! Can't find the metric to do topN over?");
    }
    // Run topN for only a single metric
    TopNQuery singleMetricQuery = new TopNQueryBuilder().copy(query)
                                                        .aggregators(condensedAggPostAggPair.lhs)
                                                        .postAggregators(condensedAggPostAggPair.rhs)
                                                        .build();
    final TopNResultBuilder singleMetricResultBuilder = BaseTopNAlgorithm.makeResultBuilder(params, singleMetricQuery);

    PooledTopNAlgorithm singleMetricAlgo = new PooledTopNAlgorithm(capabilities, singleMetricQuery, bufferPool);
    PooledTopNAlgorithm.PooledTopNParams singleMetricParam = null;
    int[] dimValSelector = null;
    try {
      singleMetricParam = singleMetricAlgo.makeInitParams(params.getDimSelector(), params.getCursor());
      singleMetricAlgo.run(
          singleMetricParam,
          singleMetricResultBuilder,
          null
      );

      // Get only the topN dimension values
      dimValSelector = getDimValSelectorForTopNMetric(singleMetricParam, singleMetricResultBuilder);
    }
    finally {
      singleMetricAlgo.cleanup(singleMetricParam);
    }

    PooledTopNAlgorithm allMetricAlgo = new PooledTopNAlgorithm(capabilities, query, bufferPool);
    PooledTopNAlgorithm.PooledTopNParams allMetricsParam = null;
    try {
      // Run topN for all metrics for top N dimension values
      allMetricsParam = allMetricAlgo.makeInitParams(params.getDimSelector(), params.getCursor());
      allMetricAlgo.run(
          allMetricsParam,
          resultBuilder,
          dimValSelector
      );
    }
    finally {
      allMetricAlgo.cleanup(allMetricsParam);
    }
  }

  @Override
  public void cleanup(TopNParams params)
  {
  }

  private int[] getDimValSelectorForTopNMetric(TopNParams params, TopNResultBuilder resultBuilder)
  {
    int[] dimValSelector = new int[params.getCardinality()];
    Arrays.fill(dimValSelector, SKIP_POSITION_VALUE);

    Iterator<DimValHolder> dimValIter = resultBuilder.getTopNIterator();
    while (dimValIter.hasNext()) {
      int dimValIndex = (Integer) dimValIter.next().getDimValIndex();
      dimValSelector[dimValIndex] = INIT_POSITION_VALUE;
    }

    return dimValSelector;
  }
}
