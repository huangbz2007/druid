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

package io.druid.indexing.overlord.http.security;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.sun.jersey.spi.container.ContainerRequest;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.overlord.TaskStorageQueryAdapter;
import io.druid.server.http.security.AbstractResourceFilter;
import io.druid.server.security.Access;
import io.druid.server.security.AuthConfig;
import io.druid.server.security.AuthorizationInfo;
import io.druid.server.security.Resource;
import io.druid.server.security.ResourceType;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

// Note - Do not use this filter at MiddleManager as TaskStorageQueryAdapter cannot be injected there
public class TaskResourceFilter extends AbstractResourceFilter
{
  @Inject
  private TaskStorageQueryAdapter taskStorageQueryAdapter;

  @Override
  public ContainerRequest filter(ContainerRequest request)
  {
    if (getAuthConfig().isEnabled()) {
      // This is an experimental feature, see - https://github.com/druid-io/druid/pull/2424

      /* Task Id is present in request path for
      *   - druid/indexer/v1/task/{taskid}/...
      */
      final String dataSourceName;

      if (request.getPath().startsWith("druid/indexer/v1/task/")) {
        final String taskId = request.getPathSegments().get(4).getPath();
        Preconditions.checkNotNull(taskId);
        Optional<Task> taskOptional = taskStorageQueryAdapter.getTask(taskId);
        if (!taskOptional.isPresent()) {
          throw new WebApplicationException(
              Response.status(Response.Status.BAD_REQUEST)
                      .entity(String.format("Cannot find any task with id: [%s]", taskId))
                      .build()
          );
        }
        dataSourceName = taskOptional.get().getDataSource();
      } else {
        throw new WebApplicationException(
            Response.serverError()
                    .entity(String.format(
                        "Do not know how to extract task information "
                        + "for authorization check for request path: [%s]",
                        request.getPath()
                    ))
                    .build()
        );
      }
      final AuthorizationInfo authorizationInfo = (AuthorizationInfo) getReq().getAttribute(AuthConfig.DRUID_AUTH_TOKEN);
      Preconditions.checkNotNull(
          authorizationInfo,
          "Security is enabled but no authorization info found in the request"
      );
      final Access authResult = authorizationInfo.isAuthorized(
          new Resource(dataSourceName, ResourceType.DATASOURCE),
          getAction(request)
      );
      if (!authResult.isAllowed()) {
        throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                                                  .entity(
                                                      String.format("Access-Check-Result: %s", authResult.toString())
                                                  )
                                                  .build());
      }
    }

    return request;
  }
}