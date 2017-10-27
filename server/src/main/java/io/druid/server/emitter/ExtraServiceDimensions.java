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

package io.druid.server.emitter;

import com.google.inject.BindingAnnotation;
import io.druid.guice.annotations.PublicApi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to inject extra dimensions, added to all events, emitted via {@link EmitterModule#getServiceEmitter}.
 *
 * Example:
 *
 * MapBinder<String, String> extraDims =
 *     MapBinder.newMapBinder(String.class, String.class, ExtraServiceDimensions.class);
 * extraDims.addBinding("foo").toInstance("bar");
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@BindingAnnotation
@PublicApi
public @interface ExtraServiceDimensions
{
}