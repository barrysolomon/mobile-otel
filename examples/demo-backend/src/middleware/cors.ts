import cors from "cors";

export const corsMiddleware = cors({
  origin: "*",
  methods: ["GET", "POST", "DELETE", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization", "X-Simulate-Error", "X-Simulate-Latency", "X-Simulate-Crash"],
});
