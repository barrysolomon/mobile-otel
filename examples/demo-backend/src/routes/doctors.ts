import { Router } from "express";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/doctors", (_req, res) => {
  const doctors = getDb().prepare("SELECT * FROM doctors").all();
  res.json(doctors);
});

export default router;
