/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.distributed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.sun.media.sound.DLSInfo;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.ElderMemberDistributedTest;
import org.apache.geode.distributed.internal.locks.DLockService;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.rules.ClusterStartupRule;
import org.apache.geode.test.dunit.rules.MemberVM;

public class GrantorFailoverDUnitTest {
  private final List<MemberVM> locators = new ArrayList<>();

  @Rule
  public ClusterStartupRule clusterStartupRule = new ClusterStartupRule();

  @Before
  public void before() {
    locators.add(clusterStartupRule.startLocatorVM(0));
    locators.add(clusterStartupRule.startLocatorVM(1));
    locators.add(clusterStartupRule.startLocatorVM(2));
  }

  @Test
  public void grantorFailsOver() {

  }

  private static volatile boolean latch1 = false;

  @Test
  public void lockRecoveryAfterGrantorDies() throws Exception {
    final String serviceName = "service name";
    final String lock1 = "lock 1";
    final String lock2 = "lock 2";

        locators.get(0).invoke(GrantorFailoverDUnitTest::assertIsElderAndGetId);

    for (MemberVM locator : locators) {
      locator.invoke(() ->
          DistributedLockService.create(serviceName,
              ClusterStartupRule.getCache().getDistributedSystem()));
    }

    // Grantor but not the elder
    final MemberVM grantorVM = locators.get(1);
    grantorVM.invoke(() -> DistributedLockService.becomeLockGrantor(serviceName));

        assertThat(locators.get(0).invoke(() -> DistributedLockService.getServiceNamed(serviceName).lock(
            lock1, 20_000, -1))).isTrue();

    assertThat(locators.get(2).invoke(() -> DistributedLockService.getServiceNamed(serviceName).lock(
        lock2, 20_000, -1))).isTrue();

    clusterStartupRule.crashVM(1);

    locators.remove(grantorVM);

    List<AsyncInvocation<Boolean>> invocations = new ArrayList<>();

    // can't get the locks again

    for (MemberVM locator : locators) {
      invocations.add(locator.invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).lock(lock1, 2, -1)));
invocations.add(locator.invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).lock(lock2, 2, -1)));
    }

    for (AsyncInvocation<Boolean> invocation : invocations) {
      assertThat(invocation.get()).isFalse();
    }

    final AsyncInvocation
        lock1FailsRelease =
        locators.get(0)
            .invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).unlock(lock1));

    final AsyncInvocation
        lock2FailsRelease =
        locators.get(2)
            .invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).unlock(lock1));


assertThatThrownBy(lock1FailsRelease.get()).isI;
lock2FailsRelease.get();

    final AsyncInvocation
        lock1SuccessfulRelease =
        locators.get(0)
            .invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).unlock(lock1));

    final AsyncInvocation
        lock2SuccessfulRelease =
        locators.get(2)
            .invokeAsync(() -> DistributedLockService.getServiceNamed(serviceName).unlock(lock1));

  }

  private static InternalDistributedMember assertIsElderAndGetId() {
    DistributionManager distributionManager =
        ClusterStartupRule.getCache().getInternalDistributedSystem().getDistributionManager();
    assertThat(distributionManager.isElder()).isTrue();
    return distributionManager.getElderId();
  }
}