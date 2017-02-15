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

package io.druid.indexing.overlord.autoscaling;

import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.inject.Inject;
import com.metamx.common.concurrent.ScheduledExecutors;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.overlord.TasksAndWorkers;
import io.druid.indexing.overlord.WorkerTaskRunner;
import io.druid.indexing.overlord.setup.TwoCloudConfig;
import io.druid.indexing.overlord.setup.WorkerBehaviorConfig;

import javax.annotation.Nullable;
import java.util.concurrent.ScheduledExecutorService;

import static io.druid.indexing.common.task.TaskLabels.getTaskLabel;

public class TwoCloudWorkerProvisioningStrategy extends AbstractWorkerProvisioningStrategy
{
  public static final Supplier<ScheduledExecutorService> EXEC_FACTORY = new Supplier<ScheduledExecutorService>()
  {
    @Override
    public ScheduledExecutorService get()
    {
      return ScheduledExecutors.fixed(1, "TwoCloudWorkerProvisioningStrategy-manager--%d");
    }
  };

  private final String taskLabel1;
  private final String ipPrefix1;
  private final PendingTaskBasedWorkerProvisioningStrategy provisioningStrategy1;
  private final String taskLabel2;
  private final String ipPrefix2;
  private final PendingTaskBasedWorkerProvisioningStrategy provisioningStrategy2;

  @Inject
  public TwoCloudWorkerProvisioningStrategy(
      Supplier<TwoCloudConfig> twoCloudConfig,
      PendingTaskBasedWorkerProvisioningConfig config,
      ProvisioningSchedulerConfig provisioningSchedulerConfig
  )
  {
    this(
        twoCloudConfig.get().getTaskLabel1(),
        twoCloudConfig.get().getIpPrefix1(),
        twoCloudConfig.get().getWorkerBehaviorConfig1(),
        twoCloudConfig.get().getTaskLabel2(),
        twoCloudConfig.get().getIpPrefix2(),
        twoCloudConfig.get().getWorkerBehaviorConfig2(),
        config,
        provisioningSchedulerConfig
    );
  }

  public TwoCloudWorkerProvisioningStrategy(
      String taskLabel1,
      String ipPrefix1,
      WorkerBehaviorConfig workerBehaviorConfig1,
      String taskLabel2,
      String ipPrefix2,
      WorkerBehaviorConfig workerBehaviorConfig2,
      PendingTaskBasedWorkerProvisioningConfig config,
      ProvisioningSchedulerConfig provisioningSchedulerConfig
  )
  {
    super(provisioningSchedulerConfig, EXEC_FACTORY);
    this.taskLabel1 = taskLabel1;
    this.taskLabel2 = taskLabel2;
    this.ipPrefix1 = ipPrefix1;
    this.ipPrefix2 = ipPrefix2;
    provisioningStrategy1 = new PendingTaskBasedWorkerProvisioningStrategy(
        config,
        Suppliers.ofInstance(workerBehaviorConfig1),
        provisioningSchedulerConfig,
        EXEC_FACTORY,
        ipPrefix1
    );
    provisioningStrategy2 = new PendingTaskBasedWorkerProvisioningStrategy(
        config,
        Suppliers.ofInstance(workerBehaviorConfig2),
        provisioningSchedulerConfig,
        EXEC_FACTORY,
        ipPrefix2
    );
  }

  @Override
  Provisioner makeProvisioner(TasksAndWorkers runner)
  {
    final TaskPredicate taskPredicate1 = new TaskPredicate(taskLabel1, true);
    final TaskPredicate taskPredicate2 = new TaskPredicate(taskLabel2, false);

    final Provisioner provisioner1 = provisioningStrategy1.makeProvisioner(
        new TasksAndWorkersFilteredByIp((WorkerTaskRunner) runner, ipPrefix1, taskPredicate1)
    );
    final Provisioner provisioner2 = provisioningStrategy2.makeProvisioner(
        new TasksAndWorkersFilteredByIp((WorkerTaskRunner) runner, ipPrefix2, taskPredicate2)
    );
    return new Provisioner()
    {
      @Override
      public boolean doTerminate()
      {
        return provisioner1.doTerminate() || provisioner2.doTerminate();
      }

      @Override
      public boolean doProvision()
      {
        return provisioner1.doProvision() || provisioner2.doProvision();
      }

      @Override
      public ScalingStats getStats()
      {
        return new ScalingStats(provisioner1.getStats(), provisioner2.getStats());
      }
    };
  }

  private class TaskPredicate implements Predicate<Task> {
    private final String taskLabel;
    private final boolean acceptNullLabel;

    public TaskPredicate(String taskLabel, boolean acceptNullLabel) {
      this.taskLabel = taskLabel;
      this.acceptNullLabel = acceptNullLabel;
    }

    @Override
    public boolean apply(@Nullable Task task)
    {
      if (task == null) {
        return false;
      }
      String label = getTaskLabel(task);
      return label == null ? acceptNullLabel : label.equals(taskLabel);
    }
  }

}