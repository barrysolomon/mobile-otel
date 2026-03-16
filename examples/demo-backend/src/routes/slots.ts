import { Router } from "express";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/slots", (req, res) => {
  const { doctor_id, date } = req.query;

  if (!doctor_id) {
    res.status(400).json({ error: "doctor_id query parameter is required", code: "MISSING_DOCTOR_ID" });
    return;
  }

  let sql = "SELECT * FROM slots WHERE doctor_id = ? AND available = 1";
  const params: string[] = [doctor_id as string];

  if (date) {
    sql += " AND date = ?";
    params.push(date as string);
  }

  sql += " ORDER BY date, time";

  const slots = getDb().prepare(sql).all(...params);
  res.json(slots);
});

export default router;
