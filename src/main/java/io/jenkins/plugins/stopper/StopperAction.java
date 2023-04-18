package io.jenkins.plugins.stopper;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Queue;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class StopperAction implements Action {
    private static final Logger LOGGER = Logger.getLogger(StopperAction.class.getName());
    private final Run target;

    public StopperAction(Run run) {
        this.target = run;
    }

    @Override
    public String getIconFileName() {
        return "/plugin/stopper/img/stop.png";
    }

    @Override
    public String getDisplayName() {
        return "Stopper";
    }

    @Override
    public String getUrlName() {
        return "stopper";
    }

    private void fetchRunAndItems(boolean isFirst, Run run, Set<Run> runs, Set<Queue.Item> items) {
        if(isFirst) {
            if(run.isBuilding()) {
                runs.add(run);

            }
            else {
//                long parent_queItem_id = run.getParent().getQueueItem().getId();
//                String parent_name = run.getParent().getName();
//                String parent_queueItem_task_name = run.getParent().getQueueItem().task.getName();
//                long parnet_queueItem_id = run.getParent().getQueueItem().getId();
                if(run.getParent().getQueueItem() != null) {
                    long runId = run.getParent().getQueueItem().getId();

//                LOGGER.log(Level.INFO, "KIKIM target's parent_queItem_id:" + parent_queItem_id + " parent_name" + parent_name + " parent_queueItem_task_name:" + parent_queueItem_task_name + " parnet_queueItem_id:" + parnet_queueItem_id);

                    Arrays.stream(Jenkins.get().getQueue().getItems()).forEach(it -> {
//                    long item_id = it.getId();
//                    String task_name = it.task.getName();
//
//                    LOGGER.log(Level.INFO, "KIKIM item item_id:"  + item_id + " task_name:" + task_name);
                        if(it.getId() == runId) {
                            items.add(it);
                        }
                    });
                }

            }
        }
        BuildCache.getCache().getDownstreamBuilds(run).forEach(r -> {
            runs.add(r);
            fetchRunAndItems(false, r, runs, items);
        });

        BuildCache.getDownstreamQueueItems(run).forEach(r -> {
            items.add(r);
        });
    }

    public Map<String, String> getChildren() {
        Set<Run> runs = ConcurrentHashMap.newKeySet();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();
        fetchRunAndItems(true, target, runs, items);

        Map<String, String> children= new HashMap<>();
        children.put("runs", "");
        children.put("items", "");

        StringBuilder sb = new StringBuilder();

        runs.forEach(r -> {
            if(r.isBuilding()) {
                sb.append(r.getParent().getFullDisplayName() + r.getDisplayName());
                sb.append(",");
            }

        });
        children.put("runs", sb.toString());

        sb.setLength(0);

        items.forEach(i -> {
            sb.append(i.task.getName());
            sb.append(",");
        });

        children.put("items", sb.toString());
        return children;
    }


    @RequirePOST
    public void doShowTest(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        Set<Run> runs = ConcurrentHashMap.newKeySet();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();
        fetchRunAndItems(true, target, runs, items);

        LOGGER.log(Level.INFO, "items ===>");
        items.forEach(i -> {
            LOGGER.log(Level.INFO, "item:" + i.task.getName());
            Jenkins.get().getQueue().cancel(i);
        });

        LOGGER.log(Level.INFO, "runs ===>");
        runs.forEach(r -> {
            LOGGER.log(Level.INFO, "run:" + r.getFullDisplayName() + " isBuilding:" + r.isBuilding());
            if(r.isBuilding()) {
                r.getExecutor().interrupt(Result.ABORTED);
            }
        });
    }

    interface worker {
        void doit();
    }

    private void processRequest(StaplerRequest req, StaplerResponse rsp, int flag) {

        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();

        Set<Run> runs = ConcurrentHashMap.newKeySet();

        fetchRunAndItems(true, target, runs, items);

        worker w1 = () -> {
            items.forEach(i -> {
                try {
                    Jenkins.get().getQueue().cancel(i);
                }
                catch (Exception e) {
                    // do nothing
                }
            });
        };

        worker w2 = () -> {
            runs.forEach(r -> {
                if(r != null && r.isBuilding()) {
                    try {
                        r.getExecutor().interrupt(Result.ABORTED);
                    }
                    catch (Exception e) {
                        // do nothing
                    }
                }
            });
        };

        if(flag == 1) {
            w1.doit();
        }

        else if(flag == 2) {
            w2.doit();
        }
        else if(flag == 3) {
            w1.doit();
            w2.doit();
        }

    }

    @RequirePOST
    public void doCancel(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 1);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doAbort(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 2);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doCancelAndAbort(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 1);
        processRequest(req, rsp, 2);
        rsp.forwardToPreviousPage(req);
    }


    @Extension
    public static class RunStopperActionFactory extends TransientActionFactory<Run> {

        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        public Collection<? extends Action> createFor(Run run) {
            return Collections.singleton(new StopperAction(run));
        }
    }

    @Extension(ordinal = 1000)
    public static class JobStopperActionFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(@Nonnull Job target) {
            Run build = target.getLastBuild();
            if (build == null) {
                return Collections.emptyList();
            } else {
                return Collections.singleton(new StopperAction(build));
            }
        }
    }

}
