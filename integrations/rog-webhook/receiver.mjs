import * as crypto from "crypto";
import * as fs from "fs";
import * as http from "http";
import * as os from "os";
import * as path from "path";
import { fileURLToPath, pathToFileURL } from "url";
import { ActivityWatchError, storeActivityWatchBatch } from "./activitywatch.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const SERVICE_NAME = "obsidian-webhook-receiver";
const DEFAULT_STATE_DIR = path.join(
  process.env.LOCALAPPDATA || path.join(os.tmpdir(), ".agents"),
  SERVICE_NAME
);

export function loadReceiverConfig(env = process.env) {
  const stateDir = env.WEBHOOK_STATE_DIR || DEFAULT_STATE_DIR;
  const secretFile = env.WEBHOOK_SECRET_FILE || path.join(stateDir, "secret.txt");
  const logFile = env.WEBHOOK_LOG_FILE || path.join(stateDir, "receiver.log");
  const notesDir = env.NOTES_DIR || path.join(__dirname, "notes");
  const uptimerDir = env.UPTIMER_DATA_DIR || path.join(__dirname, "data", "uptimer");
  const uptimerSecretFile = env.UPTIMER_SECRET_FILE || path.join(stateDir, "uptimer-secret.txt");
  const activitywatchDir = env.ACTIVITYWATCH_DATA_DIR || path.join(__dirname, "data", "activitywatch");
  const activitywatchSecretFile = env.ACTIVITYWATCH_SECRET_FILE || path.join(stateDir, "activitywatch-secret.txt");
  const host = env.WEBHOOK_BIND_HOST || "127.0.0.1";
  const port = parsePositiveInteger(env.WEBHOOK_PORT || "8788", "WEBHOOK_PORT");
  const maxBodyBytes = parsePositiveInteger(
    env.WEBHOOK_MAX_BODY_BYTES || String(1024 * 1024),
    "WEBHOOK_MAX_BODY_BYTES"
  );
  const newlineMode = (env.NEWLINE_MODE || "lf").toLowerCase();
  const newline =
    newlineMode === "crlf" ? "\r\n" : newlineMode === "none" ? "" : "\n";

  if (!new Set(["lf", "crlf", "none"]).has(newlineMode)) {
    throw new Error("NEWLINE_MODE must be lf, crlf, or none");
  }

  const secret = env.WEBHOOK_SECRET || ensureSecretFile(secretFile);
  if (Buffer.byteLength(secret, "utf8") < 32) {
    throw new Error("WEBHOOK_SECRET must contain at least 32 bytes");
  }
  const uptimerSecret = env.UPTIMER_WEBHOOK_SECRET || ensureSecretFile(uptimerSecretFile);
  if (Buffer.byteLength(uptimerSecret, "utf8") < 32) {
    throw new Error("UPTIMER_WEBHOOK_SECRET must contain at least 32 bytes");
  }
  const activitywatchSecret = env.ACTIVITYWATCH_WEBHOOK_SECRET || ensureSecretFile(activitywatchSecretFile);
  if (Buffer.byteLength(activitywatchSecret, "utf8") < 32) {
    throw new Error("ACTIVITYWATCH_WEBHOOK_SECRET must contain at least 32 bytes");
  }

  return {
    host,
    port,
    notesDir,
    uptimerDir,
    uptimerSecret,
    uptimerSecretFile,
    activitywatchDir,
    activitywatchSecret,
    activitywatchSecretFile,
    secret,
    secretFile,
    logFile,
    maxBodyBytes,
    newline,
  };
}

export function createWebhookServer(config, options = {}) {
  const logger = options.logger || createFileLogger(config.logFile);
  let writeQueue = Promise.resolve();
  const startedAt = new Date().toISOString();

  const server = http.createServer(async (request, response) => {
    const requestId = crypto.randomUUID();
    try {
      const requestUrl = new URL(request.url || "/", "http://localhost");

      if (request.method === "GET" && requestUrl.pathname === "/health") {
        sendJson(response, 200, {
          ok: true,
          service: SERVICE_NAME,
          startedAt,
        });
        return;
      }

      const isUptimerRoute = requestUrl.pathname.startsWith("/uptimer/");
      const isActivityWatch = requestUrl.pathname === "/activitywatch";
      if (!isActivityWatch && !isUptimerRoute && !requestUrl.pathname.startsWith("/webhook/")) {
        sendJson(response, 404, { ok: false, error: "not_found" });
        return;
      }

      if (request.method !== "POST") {
        response.setHeader("Allow", "POST");
        sendJson(response, 405, { ok: false, error: "method_not_allowed" });
        return;
      }

      const suppliedSecret = isActivityWatch
        ? String(request.headers.authorization || "").replace(/^Bearer /, "")
        : decodeWebhookSecret(requestUrl.pathname, isUptimerRoute ? "/uptimer/" : "/webhook/");
      const expectedSecret = isActivityWatch ? config.activitywatchSecret : isUptimerRoute ? config.uptimerSecret : config.secret;
      if (!expectedSecret || !secretsEqual(suppliedSecret, expectedSecret)) {
        logger("WARN", "Rejected request with an invalid secret", { requestId });
        sendJson(response, 401, { ok: false, error: "unauthorized" });
        return;
      }

      const body = await readRequestBody(request, config.maxBodyBytes);
      if (isActivityWatch) {
        if (String(request.headers['content-type'] || '').split(';')[0].trim().toLowerCase() !== 'application/json') {
          throw new HttpError(400, 'activitywatch_json_required');
        }
        let batch;
        try { batch = JSON.parse(body); } catch { throw new HttpError(400, 'invalid_json'); }
        writeQueue = writeQueue.catch(() => {}).then(() => storeActivityWatchBatch(config.activitywatchDir, batch));
        const receipt = await writeQueue;
        logger('INFO', 'ActivityWatch batch stored', { requestId, events: receipt.events, duplicate: receipt.duplicate });
        sendJson(response, receipt.duplicate ? 200 : 201, receipt);
        return;
      }
      const event = parseWebhookEvent(request, requestUrl, body, isUptimerRoute ? "UpTimer.md" : null);
      const legacyUptimer = event.path.replace(/\\/g, "/").replace(/^\/+/, "") === "06记录/UpTimer/UpTimer.md";
      const isUptimer = isUptimerRoute || legacyUptimer;
      if (isUptimerRoute && !event.structuredUptimer) {
        throw new HttpError(400, "uptimer_json_required");
      }
      const storagePath = isUptimer ? "UpTimer.md" : event.path;
      const absolutePath = resolveSafePath(isUptimer ? config.uptimerDir : config.notesDir, storagePath);

      writeQueue = writeQueue
        .catch(() => {})
        .then(async () => {
          await fs.promises.mkdir(path.dirname(absolutePath), { recursive: true });
          const existing = await fs.promises.stat(absolutePath).catch((error) => {
            if (error?.code === "ENOENT") return null;
            throw error;
          });
          if (existing?.isDirectory()) {
            throw new HttpError(400, "target_is_directory");
          }
          await fs.promises.appendFile(
            absolutePath,
            event.data + config.newline,
            "utf8"
          );
        });
      await writeQueue;

      logger("INFO", "Webhook appended", {
        requestId,
        path: storagePath,
        source: isUptimer ? "uptimer" : "notes",
        bytes: Buffer.byteLength(event.data, "utf8"),
      });
      sendJson(response, 201, { ok: true, requestId, path: storagePath });
    } catch (error) {
      const typedError = error instanceof HttpError || error instanceof ActivityWatchError;
      const status = typedError ? error.status : 500;
      const code = typedError ? error.code : "internal_error";
      logger(status >= 500 ? "ERROR" : "WARN", "Webhook request failed", {
        requestId,
        status,
        code,
        detail: status >= 500 ? String(error?.stack || error) : undefined,
      });
      if (!response.headersSent) {
        sendJson(response, status, { ok: false, error: code, requestId });
      } else {
        response.destroy();
      }
    }
  });

  server.keepAliveTimeout = 5_000;
  server.headersTimeout = 10_000;
  server.requestTimeout = 15_000;
  server.on("clientError", (_error, socket) => {
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
  });

  return server;
}

export function resolveSafePath(notesDir, relativePath) {
  const cleanPath = String(relativePath || "").replace(/^[/\\]+/, "");
  if (!cleanPath || cleanPath.includes("\0")) {
    throw new HttpError(400, "invalid_path");
  }

  const base = path.resolve(notesDir);
  const target = path.resolve(base, cleanPath);
  const relative = path.relative(base, target);
  if (relative.startsWith("..") || path.isAbsolute(relative) || target === base) {
    throw new HttpError(400, "path_outside_notes_dir");
  }
  return target;
}

function ensureSecretFile(secretFile) {
  fs.mkdirSync(path.dirname(secretFile), { recursive: true });
  try {
    const secret = fs.readFileSync(secretFile, "utf8").trim();
    if (!secret) throw new Error("Webhook secret file is empty");
    return secret;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }

  const secret = crypto.randomBytes(32).toString("hex");
  try {
    fs.writeFileSync(secretFile, secret + "\n", {
      encoding: "utf8",
      mode: 0o600,
      flag: "wx",
    });
  } catch (error) {
    if (error?.code !== "EEXIST") throw error;
    return fs.readFileSync(secretFile, "utf8").trim();
  }
  try {
    fs.chmodSync(secretFile, 0o600);
  } catch {}
  return secret;
}

function createFileLogger(logFile) {
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  return (level, message, fields = {}) => {
    const record = JSON.stringify({
      time: new Date().toISOString(),
      level,
      message,
      ...removeUndefined(fields),
    });
    console.log(record);
    try {
      rotateLogIfNeeded(logFile);
      fs.appendFileSync(logFile, record + "\n", "utf8");
    } catch (error) {
      console.error(`[LOGGER] ${String(error?.message || error)}`);
    }
  };
}

function rotateLogIfNeeded(logFile) {
  const stat = fs.statSync(logFile, { throwIfNoEntry: false });
  if (!stat || stat.size < 5 * 1024 * 1024) return;
  const previous = `${logFile}.1`;
  fs.rmSync(previous, { force: true });
  fs.renameSync(logFile, previous);
}

function decodeWebhookSecret(pathname, prefix = "/webhook/") {
  const encoded = pathname.slice(prefix.length);
  if (!encoded || encoded.includes("/")) return "";
  try {
    return decodeURIComponent(encoded);
  } catch {
    return "";
  }
}

function secretsEqual(left, right) {
  const leftBuffer = Buffer.from(String(left || ""), "utf8");
  const rightBuffer = Buffer.from(String(right || ""), "utf8");
  return (
    leftBuffer.length === rightBuffer.length &&
    crypto.timingSafeEqual(leftBuffer, rightBuffer)
  );
}

function readRequestBody(request, maxBodyBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let length = 0;
    let tooLarge = false;
    request.on("data", (chunk) => {
      if (tooLarge) return;
      length += chunk.length;
      if (length > maxBodyBytes) {
        tooLarge = true;
        reject(new HttpError(413, "body_too_large"));
        return;
      }
      chunks.push(chunk);
    });
    request.on("end", () => {
      if (!tooLarge) resolve(Buffer.concat(chunks).toString("utf8"));
    });
    request.on("error", reject);
  });
}

function parseWebhookEvent(request, requestUrl, body, fixedPath = null) {
  let eventPath = fixedPath || requestUrl.searchParams.get("path") || "";
  let data = body;
  let structuredUptimer = false;
  const contentType = String(request.headers["content-type"] || "")
    .split(";", 1)[0]
    .trim()
    .toLowerCase();

  if (contentType === "application/json") {
    let json;
    try {
      json = JSON.parse(body);
    } catch {
      throw new HttpError(400, "invalid_json");
    }
    if (!json || typeof json !== "object" || Array.isArray(json)) {
      throw new HttpError(400, "invalid_json_event");
    }
    eventPath ||= typeof json.path === "string" ? json.path : "";
    if (typeof json.hook_type === "string" && json.hook_type.length > 0) {
      data = JSON.stringify(json);
      structuredUptimer = true;
    }
    else if (typeof json.data === "string") data = json.data;
    else if (typeof json.content === "string") data = json.content;
    else if (typeof json.body === "string") data = json.body;
    else throw new HttpError(400, "missing_event_data");
  }

  if (!eventPath) throw new HttpError(400, "missing_path");
  return { path: eventPath, data, structuredUptimer };
}

function sendJson(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(payload);
}

function parsePositiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function removeUndefined(value) {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined)
  );
}

class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

async function main() {
  const config = loadReceiverConfig();
  fs.mkdirSync(config.notesDir, { recursive: true });
  fs.mkdirSync(config.uptimerDir, { recursive: true });
  fs.mkdirSync(config.activitywatchDir, { recursive: true });
  const logger = createFileLogger(config.logFile);
  const server = createWebhookServer(config, { logger });

  server.listen(config.port, config.host, () => {
    logger("INFO", "Receiver listening", {
      host: config.host,
      port: config.port,
      notesDir: config.notesDir,
      uptimerDir: config.uptimerDir,
      secretFile: config.secretFile,
    });
  });

  const shutdown = (signal) => {
    logger("INFO", "Receiver stopping", { signal });
    server.close((error) => {
      if (error) {
        logger("ERROR", "Receiver shutdown failed", { detail: String(error) });
        process.exitCode = 1;
      }
    });
  };
  process.once("SIGINT", () => shutdown("SIGINT"));
  process.once("SIGTERM", () => shutdown("SIGTERM"));
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  main().catch((error) => {
    console.error(error?.stack || error);
    process.exit(1);
  });
}
