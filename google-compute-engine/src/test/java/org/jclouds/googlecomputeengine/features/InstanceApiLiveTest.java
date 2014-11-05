/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.googlecomputeengine.features;

import static org.jclouds.googlecomputeengine.options.ListOptions.Builder.filter;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.jclouds.googlecomputeengine.GoogleComputeEngineApi;
import org.jclouds.googlecomputeengine.domain.Image;
import org.jclouds.googlecomputeengine.domain.Instance;
import org.jclouds.googlecomputeengine.domain.Instance.AttachedDisk;
import org.jclouds.googlecomputeengine.domain.ListPage;
import org.jclouds.googlecomputeengine.domain.Operation;
import org.jclouds.googlecomputeengine.domain.templates.InstanceTemplate;
import org.jclouds.googlecomputeengine.internal.BaseGoogleComputeEngineApiLiveTest;
import org.jclouds.googlecomputeengine.options.AttachDiskOptions;
import org.jclouds.googlecomputeengine.options.AttachDiskOptions.DiskMode;
import org.jclouds.googlecomputeengine.options.AttachDiskOptions.DiskType;
import org.jclouds.googlecomputeengine.options.DiskCreationOptions;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.inject.Module;

@Test(groups = "live", testName = "InstanceApiLiveTest")
public class InstanceApiLiveTest extends BaseGoogleComputeEngineApiLiveTest {

   private static final String INSTANCE_NETWORK_NAME = "instance-api-live-test-network";
   private static final String INSTANCE_NAME = "instance-api-live-test-instance";
   private static final String BOOT_DISK_NAME = INSTANCE_NAME + "-boot-disk";
   private static final String DISK_NAME = "instance-live-test-disk";
   private static final String IPV4_RANGE = "10.0.0.0/8";
   private static final String METADATA_ITEM_KEY = "instanceLiveTestTestProp";
   private static final String METADATA_ITEM_VALUE = "instanceLiveTestTestValue";
   private static final List<String> TAGS = ImmutableList.of("instance-live-test-tag1", "instance-live-test-tag2");
   private static final String ATTACH_DISK_NAME = "instance-api-live-test-attach-disk";
   private static final String ATTACH_DISK_DEVICE_NAME = "attach-disk-1";

   private static final int DEFAULT_DISK_SIZE_GB = 10;
   private static final int TIME_WAIT = 600;

   private InstanceTemplate instance;

   @Override
   protected GoogleComputeEngineApi create(Properties props, Iterable<Module> modules) {
      GoogleComputeEngineApi api = super.create(props, modules);
      List<Image> list = api.getImageApi("centos-cloud").list(filter("name eq centos.*")).next();
      URI imageUri = FluentIterable.from(list)
                        .filter(new Predicate<Image>() {
                           @Override
                           public boolean apply(Image input) {
                              // filter out all deprecated images
                              return !(input.deprecated() != null && input.deprecated().state() != null);
                           }
                        })
                        .first()
                        .get()
                        .selfLink();
      instance = new InstanceTemplate()
              .machineType(getDefaultMachineTypeUrl(userProject.get()))
              .addNetworkInterface(getNetworkUrl(userProject.get(), INSTANCE_NETWORK_NAME),
                                   Instance.NetworkInterface.AccessConfig.Type.ONE_TO_ONE_NAT)
              .addMetadata("mykey", "myvalue")
              .description("a description")
              .addDisk(Instance.AttachedDisk.Mode.READ_WRITE, getDiskUrl(userProject.get(), BOOT_DISK_NAME),
                       null, true, true)
              .addDisk(Instance.AttachedDisk.Mode.READ_WRITE, getDiskUrl(userProject.get(), DISK_NAME))
              .image(imageUri);

      return api;
   }

   private InstanceApi api() {
      return api.getInstanceApi(userProject.get(), DEFAULT_ZONE_NAME);
   }

   private DiskApi diskApi() {
      return api.getDiskApi(userProject.get(), DEFAULT_ZONE_NAME);
   }

   @Test(groups = "live")
   public void testInsertInstance() {

      // need to insert the network first
      assertGlobalOperationDoneSucessfully(api.getNetworkApi(userProject.get()).createInIPv4Range
              (INSTANCE_NETWORK_NAME, IPV4_RANGE), TIME_WAIT);

      DiskCreationOptions diskCreationOptions = new DiskCreationOptions().sourceImage(instance.image());
      assertZoneOperationDoneSuccessfully(diskApi().create(BOOT_DISK_NAME, DEFAULT_DISK_SIZE_GB, diskCreationOptions),
            TIME_WAIT);

      assertZoneOperationDoneSuccessfully(diskApi().create("instance-live-test-disk", DEFAULT_DISK_SIZE_GB), TIME_WAIT);
      assertZoneOperationDoneSuccessfully(api().create(INSTANCE_NAME, instance), TIME_WAIT);
   }

   @Test(groups = "live", dependsOnMethods = "testInsertInstance")
   public void testGetInstance() {

      Instance instance = api().get(INSTANCE_NAME);
      assertNotNull(instance);
      assertInstanceEquals(instance, this.instance);
   }

   @Test(groups = "live", dependsOnMethods = "testListInstance")
   public void testSetMetadataForInstance() {
      Instance originalInstance = api().get(INSTANCE_NAME);
      assertZoneOperationDoneSuccessfully(api().setMetadata(INSTANCE_NAME,
                  ImmutableMap.of(METADATA_ITEM_KEY, METADATA_ITEM_VALUE), originalInstance.metadata().fingerprint()),
            TIME_WAIT);

      Instance modifiedInstance = api().get(INSTANCE_NAME);

      assertTrue(modifiedInstance.metadata().items().containsKey(METADATA_ITEM_KEY));
      assertEquals(modifiedInstance.metadata().items().get(METADATA_ITEM_KEY),
              METADATA_ITEM_VALUE);
      assertNotNull(modifiedInstance.metadata().fingerprint());
   }

   @Test(groups = "live", dependsOnMethods = "testListInstance")
   public void testSetTagsForInstance() {
      Instance originalInstance = api().get(INSTANCE_NAME);
      assertZoneOperationDoneSuccessfully(
            api().setTags(INSTANCE_NAME, TAGS, originalInstance.tags().fingerprint()), TIME_WAIT);

      Instance modifiedInstance = api().get(INSTANCE_NAME);

      assertTrue(modifiedInstance.tags().items().containsAll(TAGS));
      assertNotNull(modifiedInstance.tags().fingerprint());
   }

   @Test(groups = "live", dependsOnMethods = "testSetMetadataForInstance")
   public void testAttachDiskToInstance() {
      assertZoneOperationDoneSuccessfully(diskApi().create(ATTACH_DISK_NAME, 1), TIME_WAIT);

      Instance originalInstance = api().get(INSTANCE_NAME);
      assertZoneOperationDoneSuccessfully(api().attachDisk(INSTANCE_NAME,
                  new AttachDiskOptions().type(DiskType.PERSISTENT)
                        .source(getDiskUrl(userProject.get(), ATTACH_DISK_NAME)).mode(DiskMode.READ_ONLY)
                        .deviceName(ATTACH_DISK_DEVICE_NAME)), TIME_WAIT);

      Instance modifiedInstance = api().get(INSTANCE_NAME);

      assertTrue(modifiedInstance.disks().size() > originalInstance.disks().size());
      assertTrue(Iterables.any(modifiedInstance.disks(), new Predicate<AttachedDisk>() {

         @Override
         public boolean apply(AttachedDisk disk) {
            return disk.type() == AttachedDisk.Type.PERSISTENT &&
                  ATTACH_DISK_DEVICE_NAME.equals(disk.deviceName());
         }
      }));
   }

   @Test(groups = "live", dependsOnMethods = "testAttachDiskToInstance")
   public void testDetachDiskFromInstance() {
      Instance originalInstance = api().get(INSTANCE_NAME);
      assertZoneOperationDoneSuccessfully(api().detachDisk(INSTANCE_NAME, ATTACH_DISK_DEVICE_NAME), TIME_WAIT);

      Instance modifiedInstance = api().get(INSTANCE_NAME);

      assertTrue(modifiedInstance.disks().size() < originalInstance.disks().size());

      assertZoneOperationDoneSuccessfully(diskApi().delete(ATTACH_DISK_NAME), TIME_WAIT);
   }

   @Test(groups = "live", dependsOnMethods = "testInsertInstance")
   public void testListInstance() {

      Iterator<ListPage<Instance>> instances = api().list(filter("name eq " + INSTANCE_NAME));

      List<Instance> instancesAsList = instances.next();

      assertEquals(instancesAsList.size(), 1);

      assertInstanceEquals(instancesAsList.get(0), instance);

   }

   @Test(groups = "live", dependsOnMethods = "testDetachDiskFromInstance")
   public void testResetInstance() {
      assertZoneOperationDoneSuccessfully(api().reset(INSTANCE_NAME), TIME_WAIT);
   }

   @Test(groups = "live", dependsOnMethods = "testResetInstance")
   public void testDeleteInstance() {
      assertZoneOperationDoneSuccessfully(api().delete(INSTANCE_NAME), TIME_WAIT);
      assertZoneOperationDoneSuccessfully(diskApi().delete(DISK_NAME), TIME_WAIT);
      assertZoneOperationDoneSuccessfully(diskApi().delete(BOOT_DISK_NAME), TIME_WAIT);
      Operation deleteNetwork = api.getNetworkApi(userProject.get()).delete(INSTANCE_NETWORK_NAME);
      assertGlobalOperationDoneSucessfully(deleteNetwork, TIME_WAIT);
   }

   private void assertInstanceEquals(Instance result, InstanceTemplate expected) {
      assertEquals(result.name(), expected.name());
      assertEquals(result.metadata().items(), expected.metadata());
   }

   @AfterClass(groups = { "integration", "live" })
   protected void tearDownContext() {
      try {
         waitZoneOperationDone(api().delete(INSTANCE_NAME), TIME_WAIT);
         waitZoneOperationDone(diskApi().delete(DISK_NAME), TIME_WAIT);
         waitZoneOperationDone(diskApi().delete(BOOT_DISK_NAME), TIME_WAIT);
         waitGlobalOperationDone(api.getNetworkApi(userProject.get()).delete(INSTANCE_NETWORK_NAME), TIME_WAIT);
      } catch (Exception e) {
         // we don't really care about any exception here, so just delete away.
       }
   }

}
