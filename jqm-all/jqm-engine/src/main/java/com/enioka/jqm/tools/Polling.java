/**
 * Copyright © 2013 enioka. All rights reserved
 * Authors: Marc-Antoine GOUILLART (marc-antoine.gouillart@enioka.com)
 *          Pierre COPPEE (pierre.coppee@enioka.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.enioka.jqm.tools;

import java.lang.management.ManagementFactory;
import java.util.Calendar;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.LockModeType;

import org.apache.log4j.Logger;

import com.enioka.jqm.jpamodel.DeploymentParameter;
import com.enioka.jqm.jpamodel.JobInstance;
import com.enioka.jqm.jpamodel.Queue;
import com.enioka.jqm.jpamodel.State;

/**
 * A thread that polls a queue according to the parameters defined inside a {@link DeploymentParameter}.
 */
class Polling implements Runnable, PollingMBean
{
    private static Logger jqmlogger = Logger.getLogger(Polling.class);
    private DeploymentParameter dp = null;
    private Queue queue = null;
    private EntityManager em = null;
    private ThreadPool tp = null;
    private boolean run = true;
    private Integer actualNbThread;
    private JqmEngine engine;
    private boolean hasStopped = false;
    private ObjectName name = null;
    private Calendar lastLoop = null;
    private Thread localThread = null;

    @Override
    public void stop()
    {
        run = false;
        if (localThread != null)
        {
            localThread.interrupt();
        }
    }

    Polling(DeploymentParameter dp, LibraryCache cache, JqmEngine engine)
    {
        jqmlogger.info("Engine " + engine.getNode().getName() + " will poll JobInstances on queue " + dp.getQueue().getName() + " every "
                + dp.getPollingInterval() / 1000 + "s with " + dp.getNbThread() + " threads for concurrent instances");
        em = Helpers.getNewEm();
        this.dp = em
                .createQuery("SELECT dp FROM DeploymentParameter dp LEFT JOIN FETCH dp.queue LEFT JOIN FETCH dp.node WHERE dp.id = :l",
                        DeploymentParameter.class).setParameter("l", dp.getId()).getSingleResult();
        this.queue = dp.getQueue();
        this.actualNbThread = 0;
        this.tp = new ThreadPool(queue, dp.getNbThread(), cache);
        this.engine = engine;

        try
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            name = new ObjectName("com.enioka.jqm:type=Node.Queue,Node=" + this.dp.getNode().getName() + ",name="
                    + this.dp.getQueue().getName());
            mbs.registerMBean(this, name);
        }
        catch (Exception e)
        {
            throw new JqmInitError("Could not create JMX beans", e);
        }
    }

    protected JobInstance dequeue()
    {
        // Free room?
        if (actualNbThread >= dp.getNbThread())
        {
            return null;
        }

        // Get the list of all jobInstance within the defined queue, ordered by position
        List<JobInstance> availableJobs = em
                .createQuery(
                        "SELECT j FROM JobInstance j LEFT JOIN FETCH j.jd WHERE j.queue = :q AND j.state = :s ORDER BY j.internalPosition ASC",
                        JobInstance.class).setParameter("q", queue).setParameter("s", State.SUBMITTED).getResultList();

        em.getTransaction().begin();
        for (JobInstance res : availableJobs)
        {
            // Lock is given when object is read, not during select... stupid.
            // So we must check if the object is still SUBMITTED.
            try
            {
                em.refresh(res, LockModeType.PESSIMISTIC_WRITE);
            }
            catch (EntityNotFoundException e)
            {
                // It has already been eaten and finished by another engine
                continue;
            }
            if (!res.getState().equals(State.SUBMITTED))
            {
                // Already eaten by another engine, not yet done
                continue;
            }

            // Highlander?
            if (res.getJd().isHighlander() && !highlanderPollingMode(res, em))
            {
                continue;
            }

            // Reserve the JI for this engine. Use a query rather than setter to avoid updating all fields (and locks when verifying FKs)
            em.createQuery(
                    "UPDATE JobInstance j SET j.state = 'ATTRIBUTED', j.node = :n, j.attributionDate = current_timestamp() WHERE id=:i")
                    .setParameter("i", res.getId()).setParameter("n", dp.getNode()).executeUpdate();

            // Stop at the first suitable JI. Release the lock & update the JI which has been attributed to us.
            em.getTransaction().commit();

            // Refresh: we have used update queries, so the cached entity is out of date.
            em.refresh(res);
            return res;
        }

        // If here, no suitable JI is available
        em.getTransaction().rollback();
        return null;
    }

    /**
     * 
     * @param jobToTest
     * @param em
     * @return true if job can be launched even if it is in highlander mode
     */
    protected boolean highlanderPollingMode(JobInstance jobToTest, EntityManager em)
    {
        List<JobInstance> jobs = em
                .createQuery(
                        "SELECT j FROM JobInstance j WHERE j IS NOT :refid AND j.jd = :jd AND (j.state = 'RUNNING' OR j.state = 'ATTRIBUTED')",
                        JobInstance.class).setParameter("refid", jobToTest).setParameter("jd", jobToTest.getJd()).getResultList();
        return jobs.isEmpty();
    }

    @Override
    public void run()
    {
        this.localThread = Thread.currentThread();
        this.localThread.setName("QUEUE_POLLER;polling;" + this.dp.getQueue().getName());
        while (true)
        {
            lastLoop = Calendar.getInstance();

            // Reset the em on each loop.
            // TODO: why is this necessary? Only for dp refresh? Cache size?
            if (em != null)
            {
                em.close();
            }
            em = Helpers.getNewEm();

            // Wait according to the deploymentParameter
            try
            {
                Thread.sleep(dp.getPollingInterval());
            }
            catch (InterruptedException e)
            {
                run = false;
            }

            // Exit if asked to
            if (!run)
            {
                break;
            }

            // Get a JI to run
            JobInstance ji = dequeue();
            while (ji != null)
            {
                // We will run this JI!
                jqmlogger.trace("JI number " + ji.getId() + " will be run by this poller this loop (already " + actualNbThread + "/"
                        + dp.getNbThread() + " on " + this.queue.getName() + ")");
                actualNbThread++;

                em.getTransaction().begin();
                Helpers.createMessage("Status updated: ATTRIBUTED", ji, em);
                em.getTransaction().commit();

                // Run it
                tp.run(ji, this);

                // Check if there is another job to run (does nothing - no db query - if queue is full so this is not expensive)
                ji = dequeue();
            }
        }
        jqmlogger.info("Poller loop on queue " + this.queue.getName() + " is stopping [engine " + this.dp.getNode().getName() + "]");
        waitForAllThreads(60 * 1000);
        em.close();
        this.tp.stop();
        this.hasStopped = true;
        jqmlogger.info("Poller on queue " + dp.getQueue().getName() + " has ended");

        // Let the engine decide if it should stop completely
        this.engine.checkEngineEnd();

        // JMX
        try
        {
            ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
        }
        catch (Exception e)
        {
            jqmlogger.error("Could not create JMX beans", e);
        }
    }

    @Override
    public Integer getCurrentActiveThreadCount()
    {
        return actualNbThread;
    }

    synchronized void decreaseNbThread()
    {
        this.actualNbThread--;
    }

    public DeploymentParameter getDp()
    {
        return dp;
    }

    boolean isRunning()
    {
        return !this.hasStopped;
    }

    private void waitForAllThreads(long timeOutMs)
    {
        long timeWaitedMs = 0;
        long stepMs = 1000;
        while (timeWaitedMs <= timeOutMs)
        {
            Long nbRunning = (Long) em
                    .createQuery(
                            "SELECT COUNT(j) FROM JobInstance j WHERE j.node = :node AND j.queue = :queue AND (j.state = 'ATTRIBUTED' OR j.state = 'RUNNING')")
                    .setParameter("node", this.dp.getNode()).setParameter("queue", this.dp.getQueue()).getSingleResult();
            jqmlogger.trace("Waiting the end of " + nbRunning + " job(s)");

            if (nbRunning == 0)
            {
                break;
            }
            if (timeWaitedMs == 0)
            {
                jqmlogger.info("Waiting for the end of " + nbRunning + " jobs on queue " + this.dp.getQueue().getName() + " - timeout is "
                        + timeOutMs + "ms");
            }
            try
            {
                Thread.sleep(stepMs);
            }
            catch (InterruptedException e)
            {
                // Interruption => stop right now
                jqmlogger.warn("Some job instances did not finish in time - wait was interrupted");
                return;
            }
            timeWaitedMs += stepMs;
        }
        if (timeWaitedMs > timeOutMs)
        {
            jqmlogger.warn("Some job instances did not finish in time - they will be killed for the poller to be able to stop");
        }
    }

    // //////////////////////////////////////////////////////////
    // JMX
    // //////////////////////////////////////////////////////////

    @Override
    public long getCumulativeJobInstancesCount()
    {
        EntityManager em = Helpers.getNewEm();
        Long nb = em.createQuery("SELECT COUNT(i) From History i WHERE i.node = :n AND i.queue = :q", Long.class)
                .setParameter("n", this.dp.getNode()).setParameter("q", this.dp.getQueue()).getSingleResult();
        em.close();
        return nb;
    }

    @Override
    public float getJobsFinishedPerSecondLastMinute()
    {
        EntityManager em2 = Helpers.getNewEm();
        Calendar minusOneMinute = Calendar.getInstance();
        minusOneMinute.add(Calendar.MINUTE, -1);
        Float nb = em2.createQuery("SELECT COUNT(i) From History i WHERE i.endDate >= :d and i.node = :n AND i.queue = :q", Long.class)
                .setParameter("d", minusOneMinute).setParameter("n", this.dp.getNode()).setParameter("q", this.dp.getQueue())
                .getSingleResult() / 60f;
        em2.close();
        return nb;
    }

    @Override
    public long getCurrentlyRunningJobCount()
    {
        EntityManager em2 = Helpers.getNewEm();
        Long nb = em2.createQuery("SELECT COUNT(i) From JobInstance i WHERE i.node = :n AND i.queue = :q", Long.class)
                .setParameter("n", this.dp.getNode()).setParameter("q", this.dp.getQueue()).getSingleResult();
        em2.close();
        return nb;
    }

    @Override
    public Integer getPollingIntervalMilliseconds()
    {
        return this.dp.getPollingInterval();
    }

    @Override
    public Integer getMaxConcurrentJobInstanceCount()
    {
        return this.dp.getNbThread();
    }

    @Override
    public boolean isActuallyPolling()
    {
        return (Calendar.getInstance().getTimeInMillis() - this.lastLoop.getTimeInMillis()) <= Math.max(2, dp.getPollingInterval());
    }

    @Override
    public boolean isFull()
    {
        return this.actualNbThread >= this.dp.getNbThread();
    }
}
