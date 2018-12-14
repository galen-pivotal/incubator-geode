/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geode.internal.statistics;

import static org.apache.geode.cache.RegionShortcut.REPLICATE;
import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_TIME_STATISTICS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_LEVEL;
import static org.apache.geode.distributed.ConfigurationProperties.STATISTIC_ARCHIVE_FILE;
import static org.apache.geode.distributed.ConfigurationProperties.STATISTIC_SAMPLING_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.statistics.micrometer.MicrometerSampleHandler;

public class MicrometerRegionIntegrationTest {

  private InternalCache cache;
  private Region<String, String> region;
  private File file;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public TestName testName = new TestName();

  @Before
  public void setUp() {
    // file = new File(temporaryFolder.getRoot(), "my.gfs");
    file = new File("/Users/klund/dev/gemfire1/open", "my.gfs");

    Properties config = new Properties();
    config.setProperty(ENABLE_TIME_STATISTICS, "true");
    config.setProperty(STATISTIC_ARCHIVE_FILE, file.getAbsolutePath());
    config.setProperty(STATISTIC_SAMPLING_ENABLED, "true");
    config.setProperty(LOG_LEVEL, "warn");

    cache = (InternalCache) new CacheFactory(config).create();

    String regionName = getClass().getSimpleName() + "_" + testName.getMethodName();
    region = cache.<String, String>createRegionFactory(REPLICATE).create(regionName);
  }

  @After
  public void tearDown() {
    cache.close();
  }

  @Test
  public void statSamplerPublishesToMicrometer() throws InterruptedException {
    for (int value = 1; value <= 100; value++) {
      region.put("key-" + value, "value-" + value);
    }

    // TODO: await couple stat samples
    Thread.sleep(5_000);

    StringBuilder stringBuilder = new StringBuilder();

    List<Meter> meters = new ArrayList<>(findMeterRegistry().getMeters());
    meters.sort(new MeterComparator());

    for (Meter meter : meters) {
      Meter.Id id = meter.getId();
      stringBuilder.append(" ").append("Meter").append("{")
          .append(" ").append("name=").append(id.getName())
          .append(" ").append("tags=").append(id.getTags())
          .append(" ").append("type=").append(id.getType())
          .append(" ").append("unit=").append(id.getBaseUnit())
          .append(" ").append("description=").append(id.getDescription())
          // .append(" ").append("class=").append(meter.getClass().getName())
          .append(" }").append(System.lineSeparator());
    }

    System.out.println("Micrometer meters: " + System.lineSeparator() + stringBuilder);
  }

  private MeterRegistry findMeterRegistry() {
    MicrometerSampleHandler handler =
        cache.getInternalDistributedSystem().getStatSampler().getSampleCollector()
            .getMicrometerSampleHandler();
    assertThat(handler).isNotNull();

    MeterRegistry registry = handler.getMeterRegistry();
    assertThat(registry).isNotNull();

    return registry;
  }

  private static class MeterComparator implements Comparator<Meter> {

    @Override
    public int compare(Meter o1, Meter o2) {
      return o1.getId().getName().compareTo(o2.getId().getName());
    }
  }
}
