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
package org.apache.solr.cloud;

import org.apache.commons.cli.CommandLine;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudHttp2SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.StreamingUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.util.ExternalPaths;
import org.apache.solr.util.SolrCLI;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.asList;
import static org.apache.solr.common.util.Utils.fromJSONString;
import static org.apache.solr.common.util.Utils.getObjectByPath;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Emulates bin/solr -e cloud -noprompt; bin/post -c gettingstarted example/exampledocs/*.xml;
 * this test is useful for catching regressions in indexing the example docs in collections that
 * use data driven functionality and managed schema features of the default configset
 * (configsets/_default).
 */
@LuceneTestCase.Nightly
public class SolrCloudExampleTest extends SolrCloudBridgeTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public SolrCloudExampleTest() {
    super();
    sliceCount = 2;
    numJettys = 2;
  }

  @BeforeClass
  public static void beforeLeaderFailureAfterFreshStartTest() {
    System.setProperty("solr.suppressDefaultConfigBootstrap", "false");
  }

  @Test
  // 12-Jun-2018 @BadApple(bugUrl="https://issues.apache.org/jira/browse/SOLR-12028") // 04-May-2018
  public void testLoadDocsIntoGettingStartedCollection() throws Exception {

    log.info("testLoadDocsIntoGettingStartedCollection initialized OK ... running test logic");

    String testCollectionName = "gettingstarted";
    File defaultConfigs = new File(ExternalPaths.DEFAULT_CONFIGSET);
    assertTrue(defaultConfigs.getAbsolutePath()+" not found!", defaultConfigs.isDirectory());

    Set<String> liveNodes = cloudClient.getZkStateReader().getLiveNodes();
    if (liveNodes.isEmpty())
      fail("No live nodes found! Cannot create a collection until there is at least 1 live node in the cluster.");
    String firstLiveNode = liveNodes.iterator().next();
    String solrUrl = cloudClient.getZkStateReader().getBaseUrlForNodeName(firstLiveNode);

    // create the gettingstarted collection just like the bin/solr script would do
    String[] args = new String[] {
        "-name", testCollectionName,
        "-shards", "2",
        "-replicationFactor", "2",
        "-confname", testCollectionName,
        "-confdir", "_default",
        "-configsetsDir", defaultConfigs.getParentFile().getParentFile().getAbsolutePath(),
        "-solrUrl", solrUrl
    };

    // NOTE: not calling SolrCLI.main as the script does because it calls System.exit which is a no-no in a JUnit test

    SolrCLI.CreateCollectionTool tool = new SolrCLI.CreateCollectionTool();
    CommandLine cli = SolrCLI.processCommandLineArgs(SolrCLI.joinCommonAndToolOptions(tool.getOptions()), args);
    log.info("Creating the '{}' collection using SolrCLI with: {}", testCollectionName, solrUrl);
    tool.runTool(cli);
    assertTrue("Collection '" + testCollectionName + "' doesn't exist after trying to create it!",
        cloudClient.getZkStateReader().getClusterState().hasCollection(testCollectionName));

    cloudClient.setDefaultCollection(testCollectionName);

    int invalidToolExitStatus = 1;
    assertEquals("Collection '" + testCollectionName + "' created even though it already existed",
        invalidToolExitStatus, tool.runTool(cli));

    // now index docs like bin/post would do but we can't use SimplePostTool because it uses System.exit when
    // it encounters an error, which JUnit doesn't like ...
    log.info("Created collection, now posting example docs!");
    File exampleDocsDir = new File(ExternalPaths.SOURCE_HOME, "example/exampledocs");
    assertTrue(exampleDocsDir.getAbsolutePath()+" not found!", exampleDocsDir.isDirectory());

    List<File> xmlFiles = Arrays.asList(exampleDocsDir.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.endsWith(".xml");
      }
    }));

    // force a deterministic random ordering of the files so seeds reproduce regardless of platform/filesystem
    Collections.sort(xmlFiles, (o1, o2) -> {
      // don't rely on File.compareTo, it's behavior varies by OS
      return o1.getName().compareTo(o2.getName());
    });
    Collections.shuffle(xmlFiles, new Random(random().nextLong()));

    // if you add/remove example XML docs, you'll have to fix these expected values
    int expectedXmlFileCount = 14;
    int expectedXmlDocCount = 32;

    assertEquals("Unexpected # of example XML files in "+exampleDocsDir.getAbsolutePath(),
                 expectedXmlFileCount, xmlFiles.size());
    
    for (File xml : xmlFiles) {
      if (log.isInfoEnabled()) {
        log.info("POSTing {}", xml.getAbsolutePath());
      }
      cloudClient.request(new StreamingUpdateRequest("/update",xml,"application/xml"));
    }
    cloudClient.commit();

    int numFound = 0;

    // give the update a chance to take effect.
    for (int idx = 0; idx < 20; ++idx) {
      QueryResponse qr = cloudClient.query(new SolrQuery("*:*"));
      numFound = (int) qr.getResults().getNumFound();
      if (numFound == expectedXmlDocCount) break;
      Thread.sleep(50);
    }
    assertEquals("*:* found unexpected number of documents", expectedXmlDocCount, numFound);

    log.info("Updating Config for {}", testCollectionName);
    doTestConfigUpdate(testCollectionName, solrUrl);

    log.info("Running healthcheck for {}", testCollectionName);
    doTestHealthcheck(testCollectionName, cloudClient.getZkHost());

    // verify the delete action works too
    log.info("Running delete for {}", testCollectionName);
    doTestDeleteAction(testCollectionName, solrUrl);

    log.info("testLoadDocsIntoGettingStartedCollection succeeded ... shutting down now!");
  }

  protected void doTestHealthcheck(String testCollectionName, String zkHost) throws Exception {
    String[] args = new String[]{
        "-collection", testCollectionName,
        "-zkHost", zkHost
    };
    SolrCLI.HealthcheckTool tool = new SolrCLI.HealthcheckTool();
    CommandLine cli =
        SolrCLI.processCommandLineArgs(SolrCLI.joinCommonAndToolOptions(tool.getOptions()), args);
    assertTrue("Healthcheck action failed!", tool.runTool(cli) == 0);
  }

  protected void doTestDeleteAction(String testCollectionName, String solrUrl) throws Exception {
    String[] args = new String[] {
        "-name", testCollectionName,
        "-solrUrl", solrUrl
    };
    SolrCLI.DeleteTool tool = new SolrCLI.DeleteTool();
    CommandLine cli =
        SolrCLI.processCommandLineArgs(SolrCLI.joinCommonAndToolOptions(tool.getOptions()), args);
    assertTrue("Delete action failed!", tool.runTool(cli) == 0);
    assertTrue(!SolrCLI.safeCheckCollectionExists(solrUrl, testCollectionName)); // it should not exist anymore
  }

  /**
   * Uses the SolrCLI config action to activate soft auto-commits for the getting started collection.
   */
  protected void doTestConfigUpdate(String testCollectionName, String solrUrl) throws Exception {
    if (!solrUrl.endsWith("/"))
      solrUrl += "/";
    String configUrl = solrUrl + testCollectionName + "/config";

    Map<String, Object> configJson = SolrCLI.getJson(configUrl);
    Object maxTimeFromConfig = SolrCLI.atPath("/config/updateHandler/autoSoftCommit/maxTime", configJson);
    assertNotNull(maxTimeFromConfig);
    assertEquals(-1L, maxTimeFromConfig);

    String prop = "updateHandler.autoSoftCommit.maxTime";
    Long maxTime = 3000L;
    String[] args = new String[]{
        "-collection", testCollectionName,
        "-property", prop,
        "-value", maxTime.toString(),
        "-solrUrl", solrUrl
    };

    Map<String, Long> startTimes = getSoftAutocommitInterval(testCollectionName);

    SolrCLI.ConfigTool tool = new SolrCLI.ConfigTool();
    CommandLine cli = SolrCLI.processCommandLineArgs(SolrCLI.joinCommonAndToolOptions(tool.getOptions()), args);
    log.info("Sending set-property '{}'={} to SolrCLI.ConfigTool.", prop, maxTime);
    assertTrue("Set config property failed!", tool.runTool(cli) == 0);

    configJson = SolrCLI.getJson(configUrl);
    maxTimeFromConfig = SolrCLI.atPath("/config/updateHandler/autoSoftCommit/maxTime", configJson);
    assertNotNull(maxTimeFromConfig);
    assertEquals(maxTime, maxTimeFromConfig);

    // Just check that we can access paths with slashes in them both through an intermediate method and explicitly
    // using atPath.
    assertEquals("Should have been able to get a value from the /query request handler",
        "explicit", SolrCLI.asString("/config/requestHandler/\\/query/defaults/echoParams", configJson));

    assertEquals("Should have been able to get a value from the /query request handler",
        "explicit", SolrCLI.atPath("/config/requestHandler/\\/query/defaults/echoParams", configJson));

    if (log.isInfoEnabled()) {
      log.info("live_nodes_count :  {}", cloudClient.getZkStateReader().getLiveNodes());
    }

    // Since it takes some time for this command to complete we need to make sure all the reloads for
    // all the cores have been done.
    boolean allGood = false;
    Map<String, Long> curSoftCommitInterval = null;
    for (int idx = 0; idx < 300 && allGood == false; ++idx) {
      curSoftCommitInterval = getSoftAutocommitInterval(testCollectionName);
      if (curSoftCommitInterval.size() > 0 && curSoftCommitInterval.size() == startTimes.size()) { // no point in even trying if they're not the same size!
        allGood = true;
        for (Map.Entry<String, Long> currEntry : curSoftCommitInterval.entrySet()) {
          if (currEntry.getValue().equals(maxTime) == false) {
            allGood = false;
          }
        }
      }
      if (allGood == false) {
        Thread.sleep(50);
      }
    }
    assertTrue("All cores should have been reloaded within 60 seconds!!!", allGood);
  }

  // Collect all of the autoSoftCommit intervals.
  private Map<String, Long> getSoftAutocommitInterval(String collection) throws Exception {
    Map<String, Long> ret = new HashMap<>();
    DocCollection coll = cloudClient.getZkStateReader().getClusterState().getCollection(collection);
    for (Slice slice : coll.getActiveSlices()) {
      for (Replica replica : slice.getReplicas()) {
        String uri = "" + replica.getCoreUrl() + "/config";
        Map respMap = getAsMap(cloudClient, uri);
        Long maxTime = (Long) (getObjectByPath(respMap, true, asList("config", "updateHandler", "autoSoftCommit", "maxTime")));
        ret.put(replica.getName(), maxTime);
      }
    }
    return ret;
  }

  private Map getAsMap(CloudHttp2SolrClient cloudClient, String uri) throws Exception {
    Http2SolrClient.SimpleResponse resp = Http2SolrClient.GET(uri, cloudClient.getHttpClient());
    return (Map) fromJSONString(resp.asString);
  }

}
