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
        LOGGER.log(Level.INFO, target.getParent().getFullDisplayName() + " runList size:" + runList.size());
        runList.forEach(r -> {
            LOGGER.log(Level.INFO, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
        });
        return runList;
    }

    public List<Integer> getNumbers() {
        List<Integer> numbers = new ArrayList<Integer>();
        runList.forEach(r -> {
            LOGGER.log(Level.INFO, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
            numbers.add(r.getNumber());
        });
        return numbers;
    }

    public ListBoxModel getPriorities() {
        ListBoxModel items = PriorityConfiguration.get().getPriorities();
        LOGGER.log(Level.INFO, "getPriorites items size:" + items.size());
        items.forEach(item -> {
            LOGGER.log(Level.INFO, "priority name:" + item.name + " value:" + item.value);
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
                // getFullDisplayName: job_name, getDisplayName: #build_num
                sb.append(r.getParent().getFullDisplayName() + r.getDisplayName());
                sb.append(",");

            }
        });
        children.put("runs", sb.toString());

        sb.setLength(0);


        items.forEach(i -> {
            sb.append(i.task.getName());
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getId());
            int curPriority = itemInfo.getPriority();
            sb.append("#p_" + curPriority);

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

    public void sort() {
        // TODO
        QueueSorter sorter = Jenkins.get().getQueue().getSorter();
//        Jenkins.get().getQueue().getBuildableItems().sort(sorter);
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
        int tmpPriority = Integer.parseInt(priority);

        int newPriority = tmpPriority == -1 ? PrioritySorterConfiguration.get().getStrategy().getDefaultPriority() : tmpPriority;

        items.forEach(i -> {
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getId());
            itemInfo.setPrioritySelection(newPriority);
            itemInfo.setWeightSelection(newPriority);
        });

        Jenkins.get().getQueue().maintain();

        rsp.forwardToPreviousPage(req);


        // Priority
        /* debug
        QueueSorter sorter = Jenkins.get().getQueue().getSorter();
        List<Queue.BuildableItem> buildableItems = Jenkins.get().getQueue().getBuildableItems();
        sorter.sortBuildableItems(buildableItems);


        Collections.sort(buildableItems, new Comparator<Queue.BuildableItem>() {
            @Override
            public int compare(Queue.BuildableItem o1, Queue.BuildableItem o2) {
                ItemInfo itemInfo1 = QueueItemCache.get().getItem(o1.getId());
                ItemInfo itemInfo2 = QueueItemCache.get().getItem(o2.getId());
                LOGGER.log(Level.INFO, "j1:" + o1.task.getName() +  " w1:" + itemInfo1.getWeight() +  " j2:" + o2.task.getName() + " w2:" + itemInfo2.getWeight());
                float diff = itemInfo1.getWeight() - itemInfo2.getWeight();

                if(diff > 0) {
                    return 1;
                }
                else if(diff < 0) {
                    return -1;
                }
                else {
                    return 0;
                }
            }
        });
        LOGGER.log(Level.INFO, "========= NEW PRIORITY FOR ITEM ==============");
        buildableItems.forEach(i -> {
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getId());
            int priority = itemInfo.getPriority();
            LOGGER.log(Level.INFO, "name:" + i.task.getName() + " priority:" + priority);
        });
        */



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


        Node newNode = Jenkins.get().getNode(node);
        if(newNode != null) {
            items.forEach(i -> {

                Label label = i.getAssignedLabel();
                if(label != null) {
                    LOGGER.log(Level.INFO, "item:" + i.task.getName() + " label:" + label.getName());

                    Iterator iter = i.getActions(LabelAssignmentAction.class).iterator();

                    Label l = null;
                    do {
                        if (!iter.hasNext()) {
                            l = i.task.getAssignedLabel();
                            LOGGER.log(Level.INFO, "item:" + i.task.getName() + " FOUND label:" + l.getName());
                            break;
                        }

                        LabelAssignmentAction laa = (LabelAssignmentAction)iter.next();
                        l = laa.getAssignedLabel(i.task);

                        if(laa instanceof ParametersAction) {
                            LOGGER.log(Level.INFO, "item:" + i.task.getName() + " laa ParametersAction");
                            Label l2 = ((ParametersAction) laa).getAssignedLabel(i.task);
//                                ((ParametersAction) laa).getParameters().forEach(parameterValue -> {
//                                    LOGGER.log(Level.INFO, "item:" + i.task.getName() + " parameterValue:" + parameterValue.toString());
//                                });
                            if(l2 != null) {
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " l2:" + l2.toString());
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " pa size:" + ((ParametersAction) laa).getParameters().size());
                                ((ParametersAction) laa).getParameters().forEach(p -> {
                                    LOGGER.log(Level.INFO, "item:" + i.task.getName() + " pa:" + p.toString() + " name:" + p.getName() + " value:" + p.getValue());
                                    if(p instanceof LabelParameterValue) {
                                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " it's LabelParameterValue");
                                    }
                                });
                            }

                        }
                    } while(l == null);

                    if(l != null) {
                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " FOUND2 label:" + l.getName());
                    }

                }

                    /*
                    if(label != null) {
                        LOGGER.log(Level.INFO, "jobname:" + i.task.getName() + " set node to:" + newNode.getNodeName());
                        Set<Node> nodes = label.getNodes();
                        nodes.clear();
                        nodes.add(newNode);
                    }
                    else {
                        LabelParameterValue paramValue = new LabelParameterValue(newNode.getNodeName(), newNode.getLabelString(), false, new AllNodeEligibility());
                        ParametersAction parametersAction = new ParametersAction(paramValue);
                        i.addAction(parametersAction);
                    }
                    */

                    /*
                    LabelParameterValue paramValue = new LabelParameterValue(newNode.getNodeName(), newNode.getLabelString(), false, new AllNodeEligibility());
                    ParametersAction parametersAction = new ParametersAction(paramValue);

                    if(label == null) {
                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " label null add action");
                        i.addAction(parametersAction);
                    }
                    else {
                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " assignedLabel:" + label.getName());
                        LabelAssignmentAction action = i.getAction(LabelAssignmentAction.class);
                        if(action instanceof ParametersAction) {
                            LOGGER.log(Level.INFO, "item:" + i.task.getName() + " action is ParametersAction");
                            ParametersAction paramAction = (ParametersAction)action;
                            List<ParameterValue> parameters = paramAction.getAllParameters();
                            LOGGER.log(Level.INFO, "item:" + i.task.getName() + " parameters size:" + parameters.size());
                            parameters.forEach(p -> {
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " parameter is:" + p.toString());
                                if(p instanceof LabelParameterValue) {
                                    i.removeAction(action);
                                    LOGGER.log(Level.INFO, "item:" + i.task.getName() + " label not null paramAction removed");
                                }
                                else {
                                    LOGGER.log(Level.INFO, "item:" + i.task.getName() + " not instance of LabelParameterValue, name:" + p.getName());
                                }
                            });
                        }

                        i.addAction(parametersAction);
                    }
                    */



                i.getAllActions().forEach(a ->{
                    if(a instanceof ParametersAction) {
                        ParametersAction action = (ParametersAction)a;
                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " action is ParametersAction");
                        List<ParameterValue> parameters = action.getAllParameters();

                        LOGGER.log(Level.INFO, "item:" + i.task.getName() + " parameters size:" + parameters.size());
                        parameters.forEach(p -> {

                            LOGGER.log(Level.INFO, "item:" + i.task.getName() + " parameter is:" + p.toString());
                            if(p instanceof LabelParameterValue) {
                                i.removeAction(action);
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " label not null paramAction removed");
                            }
                            else {
                                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " not instance of LabelParameterValue, name:" + p.getName());
                            }
                        });
                    }
                });
//                    LabelParameterValue paramValue = new LabelParameterValue(newNode.getNodeName(), newNode.getLabelString(), false, new AllNodeEligibility());
                List<String> labels = new ArrayList<>();
                labels.add(newNode.getNodeName());
                NodeParameterValue paramValue = new NodeParameterValue(newNode.getNodeName(), labels, new AllNodeEligibility());
//                    NodeParameterDefinition npd = new NodeParameterDefinition()

                ParametersAction parametersAction = new ParametersAction(paramValue);

                LOGGER.log(Level.INFO, "item:" + i.task.getName() + " DEBUG bef addAction size:" + parametersAction.getAllParameters().size());


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
