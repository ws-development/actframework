package act.job;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static act.util.SimpleProgressGauge.wsJobProgressTag;
import static org.osgl.$.F0;

import act.Act;
import act.app.App;
import act.app.event.SysEventId;
import act.event.EventBus;
import act.event.SysEventListenerBase;
import act.route.DuplicateRouteMappingException;
import act.util.DestroyableBase;
import act.util.ProgressGauge;
import act.util.SimpleProgressGauge;
import act.ws.WebSocketConnectionManager;
import org.osgl.$;
import org.osgl.exception.ConfigurationException;
import org.osgl.exception.NotAppliedException;
import org.osgl.exception.UnexpectedException;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * A `Job` is a piece of logic that can be run/scheduled in ActFramework
 */
public class Job extends DestroyableBase implements Runnable {

    private static class LockableJobList {
        boolean iterating;
        List<Job> jobList;
        Job parent;

        LockableJobList(Job parent) {
            this.jobList = new ArrayList<>();
            this.parent = parent;
        }

        synchronized void clear() {
            jobList.clear();
        }

        synchronized Job add(Job thatJob) {
            if (parent.isOneTime()) {
                thatJob.setOneTime();
            }
            if (parent.done() || iterating) {
                parent.manager.now(thatJob);
                return parent;
            }
            jobList.add(thatJob);
            return parent;
        }

        synchronized void runSubJobs() {
            runSubJobs(false);
        }

        synchronized void runSubJobs(boolean async) {
            if (jobList.isEmpty()) {
                return;
            }
            final JobManager jobManager = async ? Act.jobManager() : null;
            iterating = true;
            try {
                for (final Job subJob : jobList) {
                    if (null != jobManager) {
                        jobManager.now(new Runnable() {
                            @Override
                            public void run() {
                                subJob.run();
                            }
                        });
                    } else {
                        subJob.run();
                        if (Act.isDev() && subJob.app.hasBlockIssue()) {
                            break;
                        }
                    }
                }
            } finally {
                iterating = false;
            }
        }
    }

    static final String BRIEF_VIEW = "id,oneTime,executed,trigger";
    static final String DETAIL_VIEW = "id,oneTime,executed,trigger,worker";

    private static final C.Set<Class<? extends UnexpectedException>> FATAL_EXCEPTIONS = C.set(
            DuplicateRouteMappingException.class,
            ConfigurationException.class
    );

    private final String id;
    private final String jobProgressTag;
    private App app;
    private boolean oneTime;
    private boolean executed;
    private JobManager manager;
    private final EventBus eventBus;
    private JobTrigger trigger;
    private $.Func0<?> worker;
    Object callableResult;
    Exception callableException;
    // progress percentage
    private SimpleProgressGauge progress = new SimpleProgressGauge();
    private LockableJobList parallelJobs = new LockableJobList(this);
    private LockableJobList followingJobs = new LockableJobList(this);
    private LockableJobList precedenceJobs = new LockableJobList(this);

    private Job(String id) {
        this.id = id;
        this.jobProgressTag = wsJobProgressTag(id);
        this.eventBus = Act.eventBus();
    }

    Job(String id, JobManager manager) {
        this(id, manager, ($.Func0<?>)null);
    }

    Job(String id, JobManager manager, final Callable<?> callable) {
        this.id = id;
        this.manager = $.requireNotNull(manager);
        this.oneTime = true;
        this.app = manager.app();
        this.jobProgressTag = wsJobProgressTag(id);
        this.manager.addJob(this);
        this.eventBus = app.eventBus();
        this.worker = new F0() {
            @Override
            public Object apply() throws NotAppliedException, $.Break {
                try {
                    callableResult = callable.call();
                } catch (Exception e) {
                    callableException = e;
                }
                return null;
            }
        };
    }

    Job(String id, JobManager manager, $.Func0<?> worker) {
        this(id, manager, worker, true);
    }

    Job(String id, JobManager manager, $.Func0<?> worker, boolean oneTime) {
        this.id = id;
        this.manager = $.NPE(manager);
        this.worker = worker;
        this.oneTime = oneTime;
        this.app = manager.app();
        this.eventBus = app.eventBus();
        this.jobProgressTag = wsJobProgressTag(id);
        this.manager.addJob(this);
    }

    Job(String id, JobManager manager, $.Function<ProgressGauge, ?> worker) {
        this(id, manager, worker, true);
    }

    Job(String id, JobManager manager, $.Function<ProgressGauge, ?> worker, boolean oneTime) {
        this.id = id;
        this.manager = $.requireNotNull(manager);
        $.F1<ProgressGauge, ?> f1 = $.f1(worker);
        this.worker = f1.curry(progress);
        this.oneTime = oneTime;
        this.app = manager.app();
        this.eventBus = app.eventBus();
        this.jobProgressTag = wsJobProgressTag(id);
        this.manager.addJob(this);
    }

    public void setProgressGauge(ProgressGauge progressGauge) {
        progress = SimpleProgressGauge.wrap(progressGauge);
        progress.setId(getId());
        progress.addListener(new ProgressGauge.Listener() {
            @Override
            public void onUpdate(ProgressGauge progressGauge) {
                Map<String, ProgressGauge> payload = C.Map("act_job_progress", progressGauge);
                app.getInstance(WebSocketConnectionManager.class).sendJsonToTagged(payload, jobProgressTag);
            }
        });
    }

    public SimpleProgressGauge progress() {
        return progress;
    }

    public int getProgressInPercent() {
        return progress.currentProgressPercent();
    }

    @Override
    protected void releaseResources() {
        worker = null;
        manager = null;
        parallelJobs.clear();
        followingJobs.clear();
        precedenceJobs.clear();
        super.releaseResources();
    }

    protected String brief() {
        return S.concat("job[", id, "]\none time job:", S.string(oneTime), "\ntrigger:", S.string(trigger));
    }

    @Override
    public String toString() {
        S.Buffer sb = S.buffer(brief());
        printSubJobs(parallelJobs.jobList, "parallel jobs", sb);
        printSubJobs(followingJobs.jobList, "following jobs", sb);
        printSubJobs(precedenceJobs.jobList, "precedence jobs", sb);
        return sb.toString();
    }

    private static void printSubJobs(Collection<? extends Job> subJobs, String label, S.Buffer sb) {
        if (null != subJobs && !subJobs.isEmpty()) {
            sb.append("\n").append(label);
            for (Job job : subJobs) {
                sb.append("\n\t").append(job.brief());
            }
        }
    }

    Job setOneTime() {
        oneTime = true;
        return this;
    }

    boolean done() {
        return executed && oneTime;
    }

    final String id() {
        return id;
    }

    final void trigger(JobTrigger trigger) {
        E.NPE(trigger);
        this.trigger = trigger;
    }

    final Job addParallelJob(Job thatJob) {
        return parallelJobs.add(thatJob);
    }

    final Job addFollowingJob(Job thatJob) {
        return followingJobs.add(thatJob);
    }

    final Job addPrecedenceJob(Job thatJob) {
        return precedenceJobs.add(thatJob);
    }

    @Override
    public void run() {
        invokeParallelJobs();
        runPrecedenceJobs();
        try {
            if (Act.isDev() && app.isStarted()) {
                app.checkUpdates(false);
            }
            doJob();
        } catch (Throwable e) {
            boolean isFatal = FATAL_EXCEPTIONS.contains(e.getClass()) || Error.class.isInstance(e);
            Throwable cause = e;
            if (!isFatal) {
                cause = e.getCause();
                while (null != cause) {
                    isFatal = FATAL_EXCEPTIONS.contains(cause.getClass());
                    if (isFatal) {
                        break;
                    }
                    cause = cause.getCause();
                }
            }
            if (isFatal) {
                if (Act.isDev()) {
                    app.setBlockIssue(e);
                } else {
                    Act.shutdown(App.instance());
                    destroy();
                    if (App.instance().isMainThread()) {
                        if (cause instanceof RuntimeException) {
                            throw (RuntimeException) cause;
                        }
                        throw E.unexpected(e);
                    } else {
                        logger.fatal(cause, "Fatal error executing job %s", id());
                    }
                }
                return;
            }
            // TODO inject Job Exception Handling mechanism here
            logger.warn(e, "error executing job %s", id());
        } finally {
            if (!isDestroyed()) {
                executed = true;
                if (isOneTime()) {
                    App app = App.instance();
                    if (app.isStarted()) {
                        manager.removeJob(this);
                    } else {
                        app.eventBus().bind(SysEventId.POST_START, new SysEventListenerBase() {
                            @Override
                            public void on(EventObject event) throws Exception {
                                manager.removeJob(Job.this);
                            }
                        });
                    }
                }
                progress.destroy();
            }
        }
        runFollowingJobs();
    }

    protected void _before() {
    }

    protected void doJob(){
        JobContext.init();
        try {
            _before();
            if (null != worker) {
                worker.apply();
            }
        } finally {
            JobContext.clear();
            scheduleNextInvocation();
            _finally();
        }
    }

    protected void _finally() {}

    protected void cancel() {
        manager.cancel(id());
    }

    private void runPrecedenceJobs() {
        precedenceJobs.runSubJobs();
    }

    private void runFollowingJobs() {
        followingJobs.runSubJobs();
    }

    private void invokeParallelJobs() {
        parallelJobs.runSubJobs(true);
    }

    protected final JobManager manager() {
        return manager;
    }

    protected void scheduleNextInvocation() {
        if (null != trigger) trigger.scheduleFollowingCalls(manager(), this);
    }

    private static Job of(String jobId, final Runnable runnable, JobManager manager, boolean oneTime) {
        return new Job(jobId, manager, new F0() {
            @Override
            public Object apply() throws NotAppliedException, $.Break {
                runnable.run();
                return null;
            }
        }, oneTime);
    }

    private static Job of(final Runnable runnable, JobManager manager, boolean oneTime) {
        return of(Act.cuid(), runnable, manager, oneTime);
    }

    static Job once(final Runnable runnable, JobManager manager) {
        return of(runnable, manager, true);
    }

    static Job once(String jobId, final Runnable runnable, JobManager manager) {
        return of(jobId, runnable, manager, true);
    }

    static Job multipleTimes(final Runnable runnable, JobManager manager) {
        return of(runnable, manager, false);
    }

    static Job multipleTimes(String jobId, final Runnable runnable, JobManager manager) {
        return of(jobId, runnable, manager, false);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    // ---- Java bean accessors
    public String getId() {
        return id;
    }

    public boolean isExecuted() {
        return executed;
    }

    public boolean isOneTime() {
        return oneTime;
    }

    public JobTrigger trigger() {
        return trigger;
    }

    static Job virtualJob(String jobId, JobManager manager) {
        Job job = new Job(jobId);
        job.manager = manager;
        return job;
    }
}
