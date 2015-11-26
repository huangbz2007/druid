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

package io.druid.client;

import com.google.api.client.util.Sets;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.inject.Inject;
import com.metamx.common.logger.Logger;
import io.druid.concurrent.Execs;
import io.druid.query.DataSource;
import io.druid.server.coordination.DruidServerMetadata;
import io.druid.timeline.DataSegment;
import io.druid.timeline.VersionedIntervalTimeline;
import io.druid.timeline.partition.PartitionChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * ServerView of coordinator for the state of segments being loaded in the cluster.
 */
public class CoordinatorServerView implements TimelineInventoryView
{
  private static final Logger log = new Logger(CoordinatorServerView.class);

  private final Object lock = new Object();

  private final Map<String, Set<DruidServerMetadata>> selectors;
  private final Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines;

  private final ServerInventoryView baseView;

  private volatile boolean initialized = false;

  @Inject
  public CoordinatorServerView(
      ServerInventoryView baseView
  )
  {
    this.baseView = baseView;
    this.selectors = Maps.newHashMap();
    this.timelines = Maps.newHashMap();

    ExecutorService exec = Execs.singleThreaded("OverlordServerView-%s");
    baseView.registerSegmentCallback(
        exec,
        new ServerView.SegmentCallback()
        {
          @Override
          public ServerView.CallbackAction segmentAdded(DruidServerMetadata server, DataSegment segment)
          {
            serverAddedSegment(server, segment);
            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentRemoved(final DruidServerMetadata server, DataSegment segment)
          {
            serverRemovedSegment(server, segment);
            return ServerView.CallbackAction.CONTINUE;
          }

          @Override
          public ServerView.CallbackAction segmentViewInitialized()
          {
            initialized = true;
            return ServerView.CallbackAction.CONTINUE;
          }
        }
    );

    baseView.registerServerCallback(
        exec,
        new ServerView.ServerCallback()
        {
          @Override
          public ServerView.CallbackAction serverRemoved(DruidServer server)
          {
            removeServer(server);
            return ServerView.CallbackAction.CONTINUE;
          }
        }
    );
  }

  public boolean isInitialized()
  {
    return initialized;
  }

  public void clear()
  {
    synchronized (lock) {
      timelines.clear();
      selectors.clear();
    }
  }

  private void removeServer(DruidServer server)
  {
    for (DataSegment segment : server.getSegments().values()) {
      serverRemovedSegment(server.getMetadata(), segment);
    }
  }

  private void serverAddedSegment(final DruidServerMetadata server, final DataSegment segment)
  {
    String segmentId = segment.getIdentifier();
    synchronized (lock) {
      log.debug("Adding segment[%s] for server[%s]", segment, server);

      Set<DruidServerMetadata> servers = selectors.get(segmentId);
      if (servers == null) {
        servers = Sets.newHashSet();

        VersionedIntervalTimeline<String, SegmentLoadInfo> timeline = timelines.get(segment.getDataSource());
        if (timeline == null) {
          timeline = new VersionedIntervalTimeline<>(Ordering.natural());
          timelines.put(segment.getDataSource(), timeline);
        }

        timeline.add(
            segment.getInterval(),
            segment.getVersion(),
            segment.getShardSpec().createChunk(new SegmentLoadInfo(segment, servers))
        );
        selectors.put(segmentId, servers);
      }
      servers.add(server);
    }
  }

  private void serverRemovedSegment(DruidServerMetadata server, DataSegment segment)
  {
    String segmentId = segment.getIdentifier();
    final Set<DruidServerMetadata> servers;

    synchronized (lock) {
      log.debug("Removing segment[%s] from server[%s].", segmentId, server);

      servers = selectors.get(segmentId);
      if (servers == null) {
        log.warn("Told to remove non-existant segment[%s]", segmentId);
        return;
      }
      servers.remove(server);
      if (servers.isEmpty()) {
        VersionedIntervalTimeline<String, SegmentLoadInfo> timeline = timelines.get(segment.getDataSource());
        selectors.remove(segmentId);

        final PartitionChunk<SegmentLoadInfo> removedPartition = timeline.remove(
            segment.getInterval(), segment.getVersion(), segment.getShardSpec().createChunk(
                new SegmentLoadInfo(
                    segment,
                    Sets
                        .<DruidServerMetadata>newHashSet()
                )
            )
        );

        if (removedPartition == null) {
          log.warn(
              "Asked to remove timeline entry[interval: %s, version: %s] that doesn't exist",
              segment.getInterval(),
              segment.getVersion()
          );
        }
      }
    }
  }

  public VersionedIntervalTimeline<String, SegmentLoadInfo> getTimeline(DataSource dataSource)
  {
    String table = Iterables.getOnlyElement(dataSource.getNames());
    synchronized (lock) {
      return timelines.get(table);
    }
  }


  @Override
  public DruidServer getInventoryValue(String string)
  {
    return baseView.getInventoryValue(string);
  }

  @Override
  public Iterable<DruidServer> getInventory()
  {
    return baseView.getInventory();
  }
}
