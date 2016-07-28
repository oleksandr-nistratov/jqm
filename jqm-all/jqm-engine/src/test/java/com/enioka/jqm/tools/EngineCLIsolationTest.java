package com.enioka.jqm.tools;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import com.enioka.jqm.api.JobRequest;
import com.enioka.jqm.jpamodel.JobDefParameter;
import com.enioka.jqm.test.helpers.CreationTools;
import com.enioka.jqm.test.helpers.TestHelpers;

public class EngineCLIsolationTest extends JqmBaseTest
{
    
    /**
     * Create JobDef corresponding to TestCLIsolation.TestSet and submit it to queue
     */
    void createSubmitSetJob()
    {
        CreationTools.createJobDef(null, true, "com.enioka.jqm.TestCLIsolation.TestSet", new ArrayList<JobDefParameter>(),
                "jqm-tests/jqm-test-cl-isolation/target/test.jar", TestHelpers.qVip, -1, "TestSet", null, null, null, null, null, false,
                em);
        JobRequest.create("TestSet", null).submit();
    }

    /**
     * Create JobDef corresponding to TestCLIsolation.TestGet and submit it to queue
     */
    void createSubmitGetJob()
    {
        CreationTools.createJobDef(null, true, "com.enioka.jqm.TestCLIsolation.TestGet", new ArrayList<JobDefParameter>(),
                "jqm-tests/jqm-test-cl-isolation/target/test.jar", TestHelpers.qVip, -1, "TestGet", null, null, null, null, null, false,
                em);
        JobRequest.create("TestGet", null).submit();
    }

    /**
     * Run test without any change in the default configuration (i.e. jobs are isolated)
     */
    @Test
    public void testDefault() throws Exception
    {
        addAndStartEngine();

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(2, TestHelpers.getOkCount(em));
        Assert.assertEquals(0, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test setting global parameter launch_isolation_default to Isolated
     */
    @Test
    public void testGlobalIsolated() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "Isolated", em);

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);
        
        Assert.assertEquals(2, TestHelpers.getOkCount(em));
        Assert.assertEquals(0, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test setting global parameter launch_isolation_default to SharedJar with two jobs inside the same jar
     */
    @Test
    public void testGlobalSharedJarSame() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "SharedJar", em);

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(1, TestHelpers.getOkCount(em));
        Assert.assertEquals(1, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test setting global parameter launch_isolation_default to SharedJar with two jobs in different jars
     */
    @Test
    public void testGlobalSharedJarDifferent() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "SharedJar", em);

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        // Use get job from test-pyl jar
        CreationTools.createJobDef(null, true, "pyl.EngineCLIsolationGet", new ArrayList<JobDefParameter>(),
                "jqm-tests/jqm-test-pyl/target/test.jar", TestHelpers.qVip, -1, "EngineCLIsolationGet", null, null, null, null, null, false,
                em);
        JobRequest.create("EngineCLIsolationGet", null).submit();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(2, TestHelpers.getOkCount(em));
        Assert.assertEquals(0, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test setting global parameter launch_isolation_default to Shared with two jobs in the same jar
     */
    @Test
    public void testGlobalSharedSame() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "Shared", em);

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);
        
        Assert.assertEquals(1, TestHelpers.getOkCount(em));
        Assert.assertEquals(1, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test setting global parameter launch_isolation_default to Shared with two jobs in the different jars
     */
    @Test
    public void testGlobalSharedDifferent() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "Shared", em);

        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        // Use get job from test-pyl jar
        CreationTools.createJobDef(null, true, "pyl.EngineCLIsolationGet", new ArrayList<JobDefParameter>(),
                "jqm-tests/jqm-test-pyl/target/test.jar", TestHelpers.qVip, -1, "EngineCLIsolationGet", null, null, null, null, null, false,
                em);
        JobRequest.create("EngineCLIsolationGet", null).submit();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(1, TestHelpers.getOkCount(em));
        Assert.assertEquals(1, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test using JobDef parameter specific_isolation_context with two jobs with same specific_isolation_context values (sharing
     * expected) using default engine parameters
     */
    @Test
    public void testJobDefSpecificSame() throws Exception
    {
        addAndStartEngine();

        // TODO change jobdef to add a param
        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(1, TestHelpers.getOkCount(em));
        Assert.assertEquals(1, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test using JobDef parameter specific_isolation_context with two jobs using different specific_isolation_context values (isolation
     * expected) using default engine parameters
     */
    @Test
    public void testJobDefSpecificDifferentDefault() throws Exception
    {
        addAndStartEngine();

        // TODO change jobdef to add a param
        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(2, TestHelpers.getOkCount(em));
        Assert.assertEquals(0, TestHelpers.getNonOkCount(em));
    }

    /**
     * Run test using JobDef parameter specific_isolation_context with two jobs using different specific_isolation_context values (isolation
     * expected) setting launch_isolation_default to Shared
     */
    @Test
    public void testJobDefSpecificDifferentShared() throws Exception
    {
        addAndStartEngine();

        CreationTools.createGlobalParameter("launch_isolation_default", "Shared", em);

        // TODO change jobdef to add a param
        createSubmitSetJob();
        TestHelpers.waitFor(1, 10000, em);
        createSubmitGetJob();
        TestHelpers.waitFor(2, 10000, em);

        Assert.assertEquals(2, TestHelpers.getOkCount(em));
        Assert.assertEquals(0, TestHelpers.getNonOkCount(em));
    }
}
