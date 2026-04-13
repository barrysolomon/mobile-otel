export interface OperationFaults {
  availability: { timeout: number; errorRate: number };
  conflicts: { raceCondition: boolean };
  authorization: { timeout: number; deny: boolean };
  notifications: { partialFailure: boolean; slowChannel: string | null };
}

export interface SimulationState {
  error: boolean;
  latency: number;
  crash: boolean;
  operations: OperationFaults;
}

function defaultOperations(): OperationFaults {
  return {
    availability: { timeout: 0, errorRate: 0 },
    conflicts: { raceCondition: false },
    authorization: { timeout: 0, deny: false },
    notifications: { partialFailure: false, slowChannel: null },
  };
}

let state: SimulationState = {
  error: false, latency: 0, crash: false,
  operations: defaultOperations(),
};

export function getSimulationState(): SimulationState {
  return JSON.parse(JSON.stringify(state));
}

export function setSimulationState(update: Partial<SimulationState>): SimulationState {
  if (update.operations) {
    for (const [op, faults] of Object.entries(update.operations)) {
      const key = op as keyof OperationFaults;
      if (state.operations[key]) {
        state.operations[key] = { ...state.operations[key], ...faults } as any;
      }
    }
  }
  if (update.error !== undefined) state.error = update.error;
  if (update.latency !== undefined) state.latency = update.latency;
  if (update.crash !== undefined) state.crash = update.crash;
  return getSimulationState();
}

export function resetSimulation(): void {
  state = {
    error: false, latency: 0, crash: false,
    operations: defaultOperations(),
  };
}

export function simulateMiddleware(req: any, res: any, next: any): void {
  if (req.path.startsWith("/api/admin") || req.path === "/health") {
    next();
    return;
  }

  const shouldError = req.headers["x-simulate-error"] === "true" || state.error;
  const latencyMs = parseInt(req.headers["x-simulate-latency"] as string) || state.latency;
  const shouldCrash = req.headers["x-simulate-crash"] === "true" || state.crash;

  if (shouldCrash) {
    console.log("Simulated crash — exiting process");
    process.exit(1);
  }

  const proceed = () => {
    if (shouldError) {
      res.status(503).json({ error: "Simulated server error", code: "SIMULATED_ERROR" });
      return;
    }
    next();
  };

  if (latencyMs > 0) {
    setTimeout(proceed, latencyMs);
  } else {
    proceed();
  }
}
