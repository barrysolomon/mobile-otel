interface SimulationState {
  error: boolean;
  latency: number;
  crash: boolean;
}

let state: SimulationState = { error: false, latency: 0, crash: false };

export function getSimulationState(): SimulationState {
  return { ...state };
}

export function setSimulationState(update: Partial<SimulationState>): SimulationState {
  state = { ...state, ...update };
  return { ...state };
}

export function resetSimulation(): void {
  state = { error: false, latency: 0, crash: false };
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
