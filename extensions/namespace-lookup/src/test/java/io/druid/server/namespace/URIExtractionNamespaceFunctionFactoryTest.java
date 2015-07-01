/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.server.namespace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.metamx.common.UOE;
import com.metamx.common.lifecycle.Lifecycle;
import com.metamx.emitter.service.ServiceEmitter;
import io.druid.jackson.DefaultObjectMapper;
import io.druid.query.extraction.namespace.ExtractionNamespace;
import io.druid.query.extraction.namespace.ExtractionNamespaceFunctionFactory;
import io.druid.query.extraction.namespace.URIExtractionNamespace;
import io.druid.query.extraction.namespace.URIExtractionNamespaceTest;
import io.druid.segment.loading.DataSegmentPuller;
import io.druid.segment.loading.LocalDataSegmentPuller;
import io.druid.server.metrics.NoopServiceEmitter;
import io.druid.server.namespace.cache.NamespaceExtractionCacheManager;
import io.druid.server.namespace.cache.NamespaceExtractionCacheManagersTest;
import io.druid.server.namespace.cache.OffHeapNamespaceExtractionCacheManager;
import io.druid.server.namespace.cache.OnHeapNamespaceExtractionCacheManager;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 *
 */
@RunWith(Parameterized.class)
public class URIExtractionNamespaceFunctionFactoryTest
{
  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> getParameters() throws NoSuchMethodException
  {
    final List<Object[]> compressionParams = ImmutableList.of(
        new Object[]{
            ".dat",
            new Function<File, OutputStream>()
            {

              @Nullable
              @Override
              public OutputStream apply(@Nullable File outFile)
              {
                try {
                  return new FileOutputStream(outFile);
                }
                catch (IOException ex) {
                  throw Throwables.propagate(ex);
                }
              }
            }
        },
        new Object[]{
            ".gz",
            new Function<File, OutputStream>()
            {
              @Nullable
              @Override
              public OutputStream apply(@Nullable File outFile)
              {
                try {
                  final FileOutputStream fos = new FileOutputStream(outFile);
                  return new GZIPOutputStream(fos)
                  {
                    @Override
                    public void close() throws IOException
                    {
                      try {
                        super.close();
                      }
                      finally {
                        fos.close();
                      }
                    }
                  };
                }
                catch (IOException ex) {
                  throw Throwables.propagate(ex);
                }
              }
            }
        }
    );

    final List<Constructor<? extends NamespaceExtractionCacheManager>> cacheConstructors = ImmutableList.<Constructor<? extends NamespaceExtractionCacheManager>>of(
        OnHeapNamespaceExtractionCacheManager.class.getConstructor(Lifecycle.class, ConcurrentMap.class, ServiceEmitter.class, Map.class),
        OffHeapNamespaceExtractionCacheManager.class.getConstructor(Lifecycle.class, ConcurrentMap.class, ServiceEmitter.class, Map.class)
    );
    return new Iterable<Object[]>()
    {
      @Override
      public Iterator<Object[]> iterator()
      {
        return new Iterator<Object[]>()
        {
          Iterator<Object[]> compressionIt = compressionParams.iterator();
          Iterator<Constructor<? extends NamespaceExtractionCacheManager>> cacheConstructorIt = cacheConstructors.iterator();
          Object[] compressions = compressionIt.next();

          @Override
          public boolean hasNext()
          {
            return compressionIt.hasNext() || cacheConstructorIt.hasNext();
          }

          @Override
          public Object[] next()
          {
            if (cacheConstructorIt.hasNext()) {
              Constructor<? extends NamespaceExtractionCacheManager> constructor = cacheConstructorIt.next();
              final NamespaceExtractionCacheManager manager;
              try {
                manager = constructor.newInstance(
                    new Lifecycle(),
                    new ConcurrentHashMap<String, Function<String, String>>(),
                    new NoopServiceEmitter(),
                    new HashMap<Class<? extends ExtractionNamespace>, ExtractionNamespaceFunctionFactory<?>>()
                );
              }
              catch (Exception e) {
                throw Throwables.propagate(e);
              }
              ConcurrentHashMap<String, Function<String, String>> fnCache = new ConcurrentHashMap<String, Function<String, String>>();
              try {
                return new Object[]{
                    String.format(
                        "[%s]:[%s]",
                        compressions[0],
                        manager.getClass().getCanonicalName()
                    ), compressions[0], compressions[1], constructor
                };
              }
              catch (Exception e) {
                throw Throwables.propagate(e);
              }
            } else {
              cacheConstructorIt = cacheConstructors.iterator();
              compressions = compressionIt.next();
              return next();
            }
          }

          @Override
          public void remove()
          {
            throw new UOE("Cannot remove");
          }
        };
      }
    };
  }

  public URIExtractionNamespaceFunctionFactoryTest(
      String friendlyName,
      String suffix,
      Function<File, OutputStream> outStreamSupplier,
      Constructor<? extends NamespaceExtractionCacheManager> cacheManagerConstructor
  ) throws IllegalAccessException, InvocationTargetException, InstantiationException
  {
    final Map<Class<? extends ExtractionNamespace>, ExtractionNamespaceFunctionFactory<?>>namespaceFunctionFactoryMap  = new HashMap<>();
    this.suffix = suffix;
    this.outStreamSupplier = outStreamSupplier;
    this.lifecycle = new Lifecycle();
    this.fnCache = new ConcurrentHashMap<>();
    this.manager = cacheManagerConstructor.newInstance(lifecycle, fnCache, new NoopServiceEmitter(), namespaceFunctionFactoryMap);
    namespaceFunctionFactoryMap.put(URIExtractionNamespace.class, new URIExtractionNamespaceFunctionFactory(ImmutableMap.<String, DataSegmentPuller>of("file", new LocalDataSegmentPuller())));
  }

  private final String suffix;
  private final Function<File, OutputStream> outStreamSupplier;
  private Lifecycle lifecycle;
  private NamespaceExtractionCacheManager manager;
  private File tmpFile;
  private URIExtractionNamespaceFunctionFactory factory;
  private URIExtractionNamespace namespace;
  private ConcurrentHashMap<String, Function<String, String>> fnCache;

  @Before
  public void setUp() throws Exception
  {
    lifecycle.start();
    fnCache.clear();
    tmpFile = Files.createTempFile("druidTestURIExtractionNS", suffix).toFile();
    tmpFile.deleteOnExit();
    final ObjectMapper mapper = new DefaultObjectMapper();
    try (OutputStream ostream = outStreamSupplier.apply(tmpFile)) {
      try (OutputStreamWriter out = new OutputStreamWriter(ostream)) {
        out.write(mapper.writeValueAsString(ImmutableMap.<String, String>of("foo", "bar")));
      }
    }
    factory = new URIExtractionNamespaceFunctionFactory(
        ImmutableMap.<String, DataSegmentPuller>of("file", new LocalDataSegmentPuller())
    );
    namespace = new URIExtractionNamespace(
        "ns",
        tmpFile.toURI(),
        new URIExtractionNamespace.ObjectMapperFlatDataParser(
            URIExtractionNamespaceTest.registerTypes(new ObjectMapper())
        ),
        new Period(0),
        Pattern.quote(tmpFile.getName())
    );
  }

  @After
  public void tearDown()
  {
    lifecycle.stop();
    tmpFile.delete();
  }

  @Test
  public void simpleTest() throws IOException, ExecutionException, InterruptedException
  {
    Assert.assertNull(fnCache.get(namespace.getNamespace()));
    NamespaceExtractionCacheManagersTest.waitFor(manager.schedule(namespace));
    Function<String, String> fn = fnCache.get(namespace.getNamespace());
    while (fn == null) {
      Thread.sleep(1);
      fn = fnCache.get(namespace.getNamespace());
    }
    Assert.assertEquals("bar", fn.apply("foo"));
    Assert.assertEquals(null, fn.apply("baz"));
  }

  @Test
  public void simplePileONamespacesTest() throws InterruptedException
  {
    final int size = 128;
    List<URIExtractionNamespace> namespaces = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      URIExtractionNamespace namespace = new URIExtractionNamespace(
          String.format("%d-ns-%d", i << 10, i),
          tmpFile.toURI(),
          new URIExtractionNamespace.ObjectMapperFlatDataParser(
              URIExtractionNamespaceTest.registerTypes(new ObjectMapper())
          ),
          new Period(0),
          Pattern.quote(tmpFile.getName())
      );

      namespaces.add(namespace);

      Assert.assertNull(fnCache.get(namespace.getNamespace()));
      NamespaceExtractionCacheManagersTest.waitFor(manager.schedule(namespace));
    }

    for (int i = 0; i < size; ++i) {
      URIExtractionNamespace namespace = namespaces.get(i);
      Function<String, String> fn = fnCache.get(namespace.getNamespace());
      while (fn == null) {
        Thread.sleep(1);
        fn = fnCache.get(namespace.getNamespace());
      }
      Assert.assertEquals("bar", fn.apply("foo"));
      Assert.assertEquals(null, fn.apply("baz"));
      manager.delete(namespace.getNamespace());
      Assert.assertNull(fnCache.get(namespace.getNamespace()));
    }
  }

  @Test
  public void testLoadOnlyOnce() throws Exception
  {
    Assert.assertNull(fnCache.get(namespace.getNamespace()));

    ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
    Callable<String> populator = factory.getCachePopulator(namespace, null, map);

    String v = populator.call();
    Assert.assertEquals("bar", map.get("foo"));
    Assert.assertEquals(null, map.get("baz"));
    Assert.assertNotNull(v);

    populator = factory.getCachePopulator(namespace, v, map);
    String v2 = populator.call();
    Assert.assertEquals(v, v2);
    Assert.assertEquals("bar", map.get("foo"));
    Assert.assertEquals(null, map.get("baz"));
  }
}