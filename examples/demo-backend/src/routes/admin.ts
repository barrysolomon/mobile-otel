import { Router } from "express";
import { getSimulationState, setSimulationState, resetSimulation } from "../middleware/simulate.js";

const router = Router();

router.get("/api/admin/simulate", (_req, res) => {
  res.json(getSimulationState());
});

router.post("/api/admin/simulate", (req, res) => {
  const updated = setSimulationState(req.body);
  res.json(updated);
});

router.delete("/api/admin/simulate", (_req, res) => {
  resetSimulation();
  res.json(getSimulationState());
});

export default router;
