package io.jenkins.blueocean.service.embedded.rest;

import com.google.common.base.Predicate;
import hudson.console.AnnotatedLargeText;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BlueRun;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.visualization.table.FlowGraphTable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Implementation of {@link BluePipelineNode}.
 *
 * @author Vivek Pandey
 * @see FlowNode
 */
public class PipelineNodeImpl extends BluePipelineNode {
    private final FlowNode node;
    private final List<FlowNode> children;
    private final List<Edge> edges;
    private final WorkflowRun run;

    private final List<ErrorAction> errorActions = new ArrayList<>();

    public PipelineNodeImpl(WorkflowRun run, final FlowNode stage, List<FlowNode> children, ErrorAction... errorActions) {
        this.run = run;
        this.node = stage;
        this.children = children;
        Collections.addAll(this.errorActions, errorActions);
        this.edges = buildEdges();
    }

    @Override
    public String getId() {
        return node.getId();
    }

    @Override
    public String getDisplayName() {
        return node.getAction(ThreadNameAction.class) != null
            ? node.getAction(ThreadNameAction.class).getThreadName()
            : node.getDisplayName();
    }

    @Override
    public BlueRun.BlueRunResult getResult() {
        if(errorActions.isEmpty()){
            return PipelineStageUtil.getStatus(node,null);
        }
        // Only return the first failure (we could return more if steps are modeled in DAG
        if(!children.isEmpty() && !errorActions.isEmpty()){
            return BlueRun.BlueRunResult.UNSTABLE;
        }
        return PipelineStageUtil.getStatus(node,errorActions.get(0));
    }

    @Override
    public BlueRun.BlueRunState getStateObj() {
        return PipelineStageUtil.getState(node);
    }

    @Override
    public Date getStartTime() {
        long nodeTime = TimingAction.getStartTime(node);
        return new Date(nodeTime);
    }

    @Override
    public List<Edge> getEdges() {
        return edges;
    }

    @Override
    public Object getLog() {
        FlowGraphTable nodeGraphTable = new FlowGraphTable(run.getExecution());
        nodeGraphTable.build();

        List<FlowGraphTable.Row> rows = nodeGraphTable.getRows();

        List<AnnotatedLargeText> logs = new ArrayList<>();

        for(int i=0; i<rows.size(); i++){
            FlowGraphTable.Row row = rows.get(i);
            if(row.getNode().equals(node)){
                if(isLoggable.apply(row.getNode())){
                      logs.add(row.getNode().getAction(LogAction.class).getLogText());
                }

                for(int j=i+1; j < rows.size(); j++){
                    FlowGraphTable.Row subStepRow = rows.get(j);
                    // if it's stage collect all nodes till next stage is encountered
                    // Or if it's parallel, then wait till next parallel branch is encountered
                    if(PipelineNodeFilter.isStage.apply(node) &&
                        PipelineNodeFilter.isStage.apply(subStepRow.getNode())
                        ||
                        PipelineNodeFilter.isParallel.apply(node) &&
                            PipelineNodeFilter.isParallel.apply(subStepRow.getNode())
                        ){
                        break;
                    }else{
                        if(isLoggable.apply(subStepRow.getNode())){
                            logs.add(subStepRow.getNode().getAction(LogAction.class).getLogText());
                        }

                    }
                }
                break;
            }
        }

        return new LogResource(logs.toArray(new AnnotatedLargeText[logs.size()]));
    }

    private Predicate<FlowNode> isLoggable = new Predicate<FlowNode>() {
        @Override
        public boolean apply(@Nullable FlowNode input) {
            if(input == null)
                return false;
            return input.getAction(LogAction.class) != null;
        }
    };
    private static class EdgeImpl extends Edge{
        private final FlowNode node;
        private final FlowNode edge;

        public EdgeImpl(FlowNode node, FlowNode edge) {
            this.node = node;
            this.edge = edge;
        }

        @Override
        public String getId() {
            return edge.getId();
        }

        @Override
        public long getDurationInMillis() {
            TimingAction t = node.getAction(TimingAction.class);
            TimingAction c = edge.getAction(TimingAction.class);
            if(t!= null){
                if(c != null){
                    return c.getStartTime() - t.getStartTime();
                }
            }
            return -1;
        }
    }

    private List<Edge> buildEdges(){
        List<Edge> edges  = new ArrayList<>();
        if(!this.children.isEmpty()) {
            for (final FlowNode c : children) {
                edges.add(new EdgeImpl(node, c));
            }
        }
        return edges;
    }
}
