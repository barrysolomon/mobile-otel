import type {
  WorkflowGraph,
  GraphNode,
  GraphEdge,
  DSLConfig,
  DSLWorkflow,
  DSLTrigger,
  DSLCondition,
  DSLAction,
} from '../types/workflow';

export function compileGraphToDSL(
  graphs: WorkflowGraph[],
  limits: { diskMb: number; ramEvents: number; retentionHours: number }
): DSLConfig {
  const workflows = graphs.map((graph) => compileWorkflow(graph));

  return {
    version: 1,
    limits,
    workflows,
  };
}

function compileWorkflow(graph: WorkflowGraph): DSLWorkflow {
  // Find entry node
  const entryNode = graph.nodes.find((n) => n.id === graph.entryNodeId);
  if (!entryNode) {
    throw new Error(`Entry node ${graph.entryNodeId} not found`);
  }

  // Build trigger
  const trigger = buildTrigger(entryNode, graph.nodes, graph.edges);

  // Build actions
  const actions = buildActions(entryNode, graph.nodes, graph.edges);

  return {
    id: graph.id,
    enabled: graph.enabled,
    trigger,
    actions,
  };
}

function buildTrigger(
  node: GraphNode,
  allNodes: GraphNode[],
  edges: GraphEdge[]
): DSLTrigger {
  if (node.type === 'event_match') {
    const condition: DSLCondition = {
      event: node.data.eventName,
    };
    if (node.data.predicates && node.data.predicates.length > 0) {
      condition.where = node.data.predicates;
    }
    return { any: [condition] };
  }

  if (node.type === 'http_error_match') {
    const conditions: DSLCondition[] = [
      {
        event: 'http.response',
        where: [
          { attr: 'status', op: '>=', value: node.data.statusMin },
        ],
      },
    ];
    if (node.data.routeContains) {
      conditions.push({
        event: 'http.response',
        where: [
          { attr: 'route', op: 'contains', value: node.data.routeContains },
        ],
      });
    }
    return { all: conditions };
  }

  if (node.type === 'crash_marker') {
    return { any: [{ event: 'crash_marker' }] };
  }

  if (node.type === 'any' || node.type === 'all') {
    // Get child nodes
    const childEdges = edges.filter((e) => e.source === node.id);
    const childNodes = childEdges.map((e) =>
      allNodes.find((n) => n.id === e.target)
    ).filter((n): n is GraphNode => n !== undefined);

    const childTriggers = childNodes.map((child) =>
      buildTrigger(child, allNodes, edges)
    );

    // Flatten child triggers
    const conditions: DSLCondition[] = [];
    for (const childTrigger of childTriggers) {
      if (childTrigger.any) {
        conditions.push(...childTrigger.any);
      } else if (childTrigger.all) {
        conditions.push(...childTrigger.all);
      }
    }

    if (node.type === 'any') {
      return { any: conditions };
    } else {
      return { all: conditions };
    }
  }

  throw new Error(`Node type ${node.type} cannot be a trigger`);
}

function buildActions(
  entryNode: GraphNode,
  allNodes: GraphNode[],
  edges: GraphEdge[]
): DSLAction[] {
  const actions: DSLAction[] = [];
  const visited = new Set<string>();

  // Find all action nodes reachable from entry
  const findActionNodes = (nodeId: string) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);

    const node = allNodes.find((n) => n.id === nodeId);
    if (!node) return;

    // If it's an action node, add it
    if (
      node.type === 'annotate_trigger' ||
      node.type === 'flush_window' ||
      node.type === 'set_sampling'
    ) {
      actions.push(nodeToAction(node));
    }

    // Follow edges
    const outgoingEdges = edges.filter((e) => e.source === nodeId);
    for (const edge of outgoingEdges) {
      findActionNodes(edge.target);
    }
  };

  findActionNodes(entryNode.id);

  return actions;
}

function nodeToAction(node: GraphNode): DSLAction {
  if (node.type === 'annotate_trigger') {
    return {
      type: 'annotate_trigger',
      trigger_id: node.data.triggerId,
      reason: node.data.reason,
    };
  }

  if (node.type === 'flush_window') {
    return {
      type: 'flush_window',
      minutes: node.data.minutes,
      scope: node.data.scope,
    };
  }

  if (node.type === 'set_sampling') {
    return {
      type: 'set_sampling',
      rate: node.data.rate,
      duration_minutes: node.data.durationMinutes,
    };
  }

  throw new Error(`Node type ${node.type} is not an action`);
}

export function validateGraph(graph: WorkflowGraph): string[] {
  const errors: string[] = [];

  // Check entry node exists
  if (!graph.nodes.find((n) => n.id === graph.entryNodeId)) {
    errors.push('Entry node not found');
  }

  // Check no cycles
  if (hasCycle(graph.nodes, graph.edges)) {
    errors.push('Graph contains cycles');
  }

  // Check all edges connect valid nodes
  for (const edge of graph.edges) {
    if (!graph.nodes.find((n) => n.id === edge.source)) {
      errors.push(`Edge source ${edge.source} not found`);
    }
    if (!graph.nodes.find((n) => n.id === edge.target)) {
      errors.push(`Edge target ${edge.target} not found`);
    }
  }

  // Check trigger nodes connect to logic or action
  const triggerTypes = ['event_match', 'http_error_match', 'crash_marker'];
  for (const node of graph.nodes) {
    if (triggerTypes.includes(node.type)) {
      const outgoing = graph.edges.filter((e) => e.source === node.id);
      if (outgoing.length === 0) {
        errors.push(`Trigger node ${node.id} has no outgoing edges`);
      }
    }
  }

  return errors;
}

function hasCycle(nodes: GraphNode[], edges: GraphEdge[]): boolean {
  const visited = new Set<string>();
  const recStack = new Set<string>();

  const dfs = (nodeId: string): boolean => {
    visited.add(nodeId);
    recStack.add(nodeId);

    const outgoing = edges.filter((e) => e.source === nodeId);
    for (const edge of outgoing) {
      if (!visited.has(edge.target)) {
        if (dfs(edge.target)) return true;
      } else if (recStack.has(edge.target)) {
        return true;
      }
    }

    recStack.delete(nodeId);
    return false;
  };

  for (const node of nodes) {
    if (!visited.has(node.id)) {
      if (dfs(node.id)) return true;
    }
  }

  return false;
}
