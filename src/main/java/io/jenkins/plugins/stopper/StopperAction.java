package io.jenkins.plugins.stopper;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import com.iwombat.util.StringUtil;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.labels.LabelAssignmentAction;
import hudson.model.queue.QueueSorter;
import hudson.remoting.Callable;
import hudson.slaves.NodeDescriptor;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.ClockDifference;
import hudson.util.DescribableList;
import hudson.util.ListBoxModel;
import hudson.util.RunList;
import jenkins.advancedqueue.PriorityConfiguration;
import jenkins.advancedqueue.PrioritySorterConfiguration;
import jenkins.advancedqueue.priority.strategy.PriorityJobProperty;
import jenkins.advancedqueue.sorter.ItemInfo;
import jenkins.advancedqueue.sorter.QueueItemCache;
import jenkins.advancedqueue.sorter.StartedJobItemCache;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.apache.commons.collections.list.TreeList;
import org.apache.commons.lang.StringUtils;
import org.jvnet.jenkins.plugins.nodelabelparameter.LabelParameterValue;
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterDefinition;
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue;
import org.jvnet.jenkins.plugins.nodelabelparameter.node.AllNodeEligibility;
import org.jvnet.jenkins.plugins.nodelabelparameter.parameterizedtrigger.NodeLabelBuildParameter;
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
    private List<Run> runList;

    public StopperAction(Run run, List<Run> runList) {
        this.target = run;
        this.runList = runList;

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

    private void filterRunAndItems(StaplerRequest req, Set<Run> runs, Set<Queue.Item> items) {
        LOGGER.log(Level.INFO, "debug req paramerters");
        req.getParameterMap().entrySet().forEach(entry -> {
            LOGGER.log(Level.INFO, "key:" + entry.getKey() + " v:" + entry.getValue());
        });

        List<String> qtems = new ArrayList<>();
        List<String> builds = new ArrayList<>();;

        if(req.getParameter("qtems") != null) {
            LOGGER.log(Level.INFO, "Reuqested cancel, raw qtems=>" + req.getParameter("qtems"));
            qtems = Arrays.asList(req.getParameter("qtems").split(","));
        }
        if(req.getParameter("builds") != null) {
            LOGGER.log(Level.INFO, "Reuqested abort, raw builds=>" + req.getParameter("builds"));
            builds =  Arrays.asList(req.getParameter("builds").split(","));
        }

        Set<Run> newRuns = new HashSet<>();
        Set<Queue.Item> newItems = new HashSet<>();

        builds.stream().filter(t -> t.trim().length() > 0).forEach(t -> {
            String[] parray = t.split(":");
            if(parray.length > 0) {
                String build = parray[0];
                runs.stream().filter(r -> r.getParent().getFullDisplayName().equals(build)).forEach(r -> {
                    newRuns.add(r);
                });

            }
        });

        qtems.stream().filter(t -> t.trim().length() > 0).forEach(t -> {
            String[] parray = t.split(":");
            if(parray.length > 0) {
                String job = parray[0];
                items.stream().filter(i -> i.task.getName().equals(job)).forEach(i -> {
                    newItems.add(i);
                });

            }
        });

        runs.clear();;
        runs.addAll(newRuns);

        items.clear();;
        items.addAll(newItems);


    }

    private void fetchRunAndItems(boolean isFirst, Run run, Set<Run> runs, Set<Queue.Item> items) {
        if(isFirst) {

            if(run.isBuilding()) {
                runs.add(run);
            }
            else {
                if(run.getParent().getQueueItem() != null) {
                    long runId = run.getParent().getQueueItem().getId();
                    Arrays.stream(Jenkins.get().getQueue().getItems()).forEach(it -> {
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

    public List<Run> getRunList() {
        LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " runList size:" + runList.size());
        runList.forEach(r -> {
            LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
        });

        return runList;
    }

    public List<Integer> getNumbers() {
        List<Integer> numbers = new ArrayList<Integer>();
        runList.forEach(r -> {
            LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
            numbers.add(r.getNumber());
        });
        return numbers;
    }

    public ListBoxModel getPriorities() {
        ListBoxModel items = PriorityConfiguration.get().getPriorities();
        LOGGER.log(Level.FINE, "getPriorites items size:" + items.size());
        items.forEach(item -> {
            LOGGER.log(Level.FINE, "priority name:" + item.name + " value:" + item.value);
        });
        return items;
    }

    public List<String> getNodes() {
        List<String> nodes = new ArrayList<>();
        nodes.add("built-in");
        Jenkins.get().getNodes().forEach(n -> {
            nodes.add(n.getNodeName());
        });
        return nodes;
    }

    public Map<String, String> getChildren(int buildNumber) {
        Map<String, String> children= new HashMap<>();
        children.put("runs", "");
        children.put("items", "");

        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return children;
        }

        //
        Set<Run> runs = ConcurrentHashMap.newKeySet();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();

        Run target = optRun.get();
        String fullDisplayName = target.getFullDisplayName();
        String displayName = target.getDisplayName();
        int number = target.getNumber();
        LOGGER.log(Level.INFO, "TARGET fullDisplayName:" + fullDisplayName +" displayName:" + displayName + " number:" + number);

        fetchRunAndItems(true, target, runs, items);

        StringBuilder sb = new StringBuilder();

        runs.forEach(r -> {
            if(r.isBuilding()) {
                sb.append(r.getParent().getFullDisplayName());

                // priority
                sb.append(":");
                ItemInfo itemInfo = StartedJobItemCache.get().getStartedItem(r.getParent().getFullDisplayName(), r.getNumber());
                sb.append("P" + itemInfo.getPriority());

                // build number
                sb.append(":");
                sb.append("#" + r.getNumber());

                sb.append(",");
            }
        });
        children.put("runs", sb.toString());

        sb.setLength(0);


        items.forEach(i -> {
            sb.append(i.task.getName());

            // priority
            sb.append(":");
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getId());
            int curPriority = itemInfo.getPriority();
            sb.append("P" + curPriority);

            // label
            Label label = i.getAssignedLabel();
            if(label != null) {
                sb.append(":");
                sb.append("L" + label.getName());
            }

            sb.append(",");
            if(i.isBlocked()) {
                LOGGER.log(Level.INFO, i.task.getName() + " blocked");
            }
            if(i.isStuck()) {
                LOGGER.log(Level.INFO, i.task.getName() + " stucked");
            }

        });

        children.put("items", sb.toString());

        return children;
    }

    interface worker {
        void doit();
    }

    private void processRequest(StaplerRequest req, StaplerResponse rsp, int flag) {
        String tmpBuildNumber = req.getParameter("buildNumber");

        if(StringUtils.isBlank(tmpBuildNumber)) {
            return;
        }

        int buildNumber = Integer.parseInt(tmpBuildNumber);

        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        //
        Run target = optRun.get();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();
        Set<Run> runs = ConcurrentHashMap.newKeySet();

        fetchRunAndItems(true, target, runs, items);
        filterRunAndItems(req, runs, items);

        worker w1 = () -> {
            items.forEach(i -> {
                LOGGER.log(Level.INFO, "cancel job:" + i.task.getName());
                try {
                    Jenkins.get().getQueue().cancel(i);
                }
                catch (Exception e) {
                    LOGGER.log(Level.SEVERE, i.task.getName() + " cancel error");
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            });
        };

        worker w2 = () -> {
            runs.forEach(r -> {
                if(r != null && r.isBuilding()) {
                    LOGGER.log(Level.INFO, "abort job:" + r.getFullDisplayName());
                    try {
                        r.getExecutor().interrupt(Result.ABORTED);
                    }
                    catch (Exception e) {
                        LOGGER.log(Level.SEVERE, r.getFullDisplayName() + " abort error");
                        LOGGER.log(Level.SEVERE, e.getMessage(), e);
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

    @RequirePOST
    public void doUpdatePriority(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        String tmpBuildNumber = req.getParameter("buildNumber");
        if(StringUtils.isBlank(tmpBuildNumber)) {
            rsp.forwardToPreviousPage(req);
            return;
        }


        String priority = req.getParameter("priority");
        if(StringUtils.isBlank(priority)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        //
        int buildNumber = Integer.parseInt(tmpBuildNumber);
        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        Run target = optRun.get();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();
        Set<Run> runs = ConcurrentHashMap.newKeySet();

        fetchRunAndItems(true, target, runs, items);
        filterRunAndItems(req, runs, items);

        int tmpPriority = Integer.parseInt(priority);
        int newPriority = tmpPriority == -1 ? PrioritySorterConfiguration.get().getStrategy().getDefaultPriority() : tmpPriority;

        items.forEach(i -> {
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getId());
            itemInfo.setPrioritySelection(newPriority);
            itemInfo.setWeightSelection(newPriority);
        });

        Jenkins.get().getQueue().maintain();

        rsp.forwardToPreviousPage(req);

    }

    @RequirePOST
    public void doUpdateNode(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        String tmpBuildNumber = req.getParameter("buildNumber");
        if(StringUtils.isBlank(tmpBuildNumber)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        String node = req.getParameter("node");
        if(StringUtils.isBlank(node)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        //
        int buildNumber = Integer.parseInt(tmpBuildNumber);
        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        Run target = optRun.get();
        Set<Queue.Item> items = ConcurrentHashMap.newKeySet();
        Set<Run> runs = ConcurrentHashMap.newKeySet();

        fetchRunAndItems(true, target, runs, items);
        filterRunAndItems(req, runs, items);

        Node newNode = Jenkins.get().getNode(node);
        if(newNode != null) {
            items.forEach(i -> {

                // NodeLabelParameter plugin use ParametersAction as LabelAssignmentAction.
                // If exists LabelParameterValue, remove it.
                i.getActions(ParametersAction.class).forEach(action -> {
                    List<ParameterValue> parameters = action.getAllParameters();
                    parameters.forEach(p -> {
                        if(p instanceof LabelParameterValue) {
                            i.removeAction(action);
                        }
                    });
                });

                // Add requested node
                List<String> labels = new ArrayList<>();
                labels.add(newNode.getNodeName());
                NodeParameterValue paramValue = new NodeParameterValue(newNode.getNodeName(), labels, new AllNodeEligibility());
                ParametersAction parametersAction = new ParametersAction(paramValue);
                i.addAction(parametersAction);
            });
        }
        else {
            if(node.equals("built-in") || node.equals("default")) {
                items.forEach(i -> {
                    i.getActions(ParametersAction.class).forEach(action -> {
                        action.getAllParameters().forEach(p -> {
                            if(p instanceof LabelParameterValue) {
                                i.removeAction(action);
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " built-in, old paramAction removed");
                            }
                        });
                    });

                });

            }
            else { // for debugging
                LOGGER.log(Level.SEVERE, "node:" + node + " not found");
                LOGGER.log(Level.INFO, "Jenkins has nodes =>");
                for(Node n: Jenkins.get().getNodes()) {
                    LOGGER.log(Level.INFO, n.getNodeName());
                }
                LOGGER.log(Level.INFO, "<= Jenkins has nodes");
            }

        }

        Jenkins.get().getQueue().maintain();


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

            List<Run> runList = new ArrayList<>();
            runList.add(run);
            return Collections.singleton(new StopperAction(run, runList));
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

            List<Run> runList = target.getBuilds();
            runList = ((RunList<Run>) runList).filter(r -> r.isBuilding());

            ArrayList<Run> runArrayList = new ArrayList<>(runList);

            Collections.sort(runArrayList, new Comparator<Run>() {
                @Override
                public int compare(Run o1, Run o2) {
                    return o1.getNumber() - o2.getNumber();
                }
            });

            Run build = target.getLastBuild();

            if (build == null) {
                return Collections.emptyList();
            } else {
                return Collections.singleton(new StopperAction(build, runArrayList));
            }
        }
    }

}
