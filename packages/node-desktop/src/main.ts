/**
 * ApexPanda 桌面节点 - Electron 主进程
 * 连接 Gateway，暴露 camera/screen/canvas/system 能力
 */
(function filterLibpngWarnings() {
  const orig = process.stderr.write;
  process.stderr.write = function (this: NodeJS.WriteStream, chunk: Parameters<typeof orig>[0], ...args: unknown[]) {
    const s = typeof chunk === 'string' ? chunk : chunk.toString();
    if (s.includes('libpng warning: iCCP')) return true;
    return (orig as Function).apply(this, [chunk, ...args]);
  } as typeof orig;
})();

import { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, clipboard, desktopCapturer, screen } from 'electron';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, execSync } from 'node:child_process';
import { platform } from 'node:os';
import { WebSocket } from 'ws';
import { mkdir, readFile, writeFile, readdir, stat } from 'node:fs/promises';

const __dirname = dirname(fileURLToPath(import.meta.url));
const isDev = process.env.NODE_ENV !== 'production';

const ASSETS_DIR = join(__dirname, '..', 'assets');
// Windows 任务栏/标题栏用 ICO 多分辨率更清晰
const WINDOW_ICON_PATH = platform() === 'win32'
  ? join(ASSETS_DIR, 'icon.ico')
  : join(ASSETS_DIR, 'icon.png');

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 60_000;
const CAPABILITIES = [
  'camera.snap',
  'camera.clip',
  'screen.record',
  'canvas.snapshot',
  'canvas.navigate',
  'system.run',
  'system.which',
  'system.readFile',
  'system.writeFile',
  'system.listDir',
  'system.clipboardRead',
  'system.clipboardWrite',
  'system.processList',
  'system.processKill',
  'screen.ocr',
  'ui.tap',
  'ui.input',
  'ui.dump',
];

function parseGatewayWsUrl(httpUrl: string): string {
  let u = (httpUrl ?? '').trim().replace(/^http:\/\//, 'ws://').replace(/^https:\/\//, 'wss://');
  if (!u.startsWith('ws')) return `ws://${httpUrl}`;
  u = u.replace(/^ws:\/\/localhost\b/, 'ws://127.0.0.1').replace(/^wss:\/\/localhost\b/, 'wss://127.0.0.1');
  const url = new URL(u);
  url.pathname = url.pathname.replace(/\/$/, '') || '/';
  if (!url.pathname.endsWith('/ws')) {
    url.pathname = url.pathname + (url.pathname.endsWith('/') ? '' : '/') + 'ws';
  }
  url.searchParams.set('role', 'node');
  return url.toString();
}

function getDataDir(): string {
  const base = process.env.APEXPANDA_DATA_DIR ?? join(app.getPath('userData'), '.apexpanda');
  return base;
}

function getNodeConfigPath(): string {
  return join(getDataDir(), 'node.json');
}

interface NodeConfig {
  deviceId?: string;
  token?: string;
  displayName?: string;
  gatewayUrl?: string;
  launchAtStartup?: boolean;
}

async function loadNodeConfig(): Promise<NodeConfig> {
  try {
    const raw = await readFile(getNodeConfigPath(), 'utf-8');
    return JSON.parse(raw) as NodeConfig;
  } catch {
    return {};
  }
}

async function saveNodeConfig(cfg: NodeConfig): Promise<void> {
  await mkdir(getDataDir(), { recursive: true });
  await writeFile(getNodeConfigPath(), JSON.stringify(cfg, null, 2), 'utf-8');
}

const isWindows = platform() === 'win32';

interface ApprovalRule {
  command?: string;
  args?: string[];
  pattern?: string;
  comment?: string;
}

interface ExecApprovals {
  mode?: 'full' | 'blacklist' | 'remote-approve';
  rules?: ApprovalRule[];  // 黑名单规则，仅 mode=blacklist 时生效
}

async function loadExecApprovals(): Promise<ExecApprovals> {
  try {
    const path = join(getDataDir(), 'exec-approvals.json');
    const raw = await readFile(path, 'utf-8');
    return JSON.parse(raw) as ExecApprovals;
  } catch {
    return { mode: 'full', rules: [] };
  }
}

async function saveExecApprovals(data: ExecApprovals): Promise<void> {
  await mkdir(getDataDir(), { recursive: true });
  await writeFile(join(getDataDir(), 'exec-approvals.json'), JSON.stringify(data, null, 2), 'utf-8');
}

function matchRule(cmd: string, args: string[], rule: ApprovalRule): boolean {
  const fullCmd = isWindows && args?.length >= 2 ? args[1] : [cmd, ...(args ?? [])].join(' ');
  if (rule.command) {
    const cmdNorm = isWindows ? cmd.replace(/\\/g, '/') : cmd;
    const ruleNorm = (rule.command ?? '').replace(/\\/g, '/');
    if (cmdNorm !== ruleNorm && !cmdNorm.endsWith(ruleNorm)) return false;
    if (rule.args && rule.args.length > 0) {
      if (!args || args.length < rule.args.length) return false;
      for (let i = 0; i < rule.args.length; i++) {
        if (args[i] !== rule.args[i]) return false;
      }
    }
    return true;
  }
  if (rule.pattern) {
    try {
      return new RegExp(rule.pattern).test(fullCmd);
    } catch {
      return false;
    }
  }
  return false;
}

/** 是否在黑名单中（mode=blacklist 时，命中 rules 则禁止执行） */
async function isBlacklisted(bin: string, args: string[]): Promise<boolean> {
  const approvals = await loadExecApprovals();
  if (approvals.mode !== 'blacklist' || !approvals.rules?.length) return false;
  return approvals.rules.some((r) => matchRule(bin, args, r));
}

async function getExecMode(): Promise<'full' | 'blacklist' | 'remote-approve'> {
  const envMode = process.env.APEXPANDA_EXEC_MODE?.toLowerCase();
  if (envMode === 'remote-approve' || envMode === 'remote_approve') return 'remote-approve';
  if (envMode === 'blacklist') return 'blacklist';
  const cfg = await loadExecApprovals();
  const m = cfg.mode?.toLowerCase();
  if (m === 'blacklist' || m === 'remote-approve') return m;
  return 'full';  // full 或旧 allowlist 均视为全部允许
}

const pendingExecApprovals = new Map<string, {
  resolve: (approved: boolean) => void;
  timer: ReturnType<typeof setTimeout>;
}>();
const EXEC_APPROVAL_TIMEOUT_MS = 30_000;

async function runSystemRun(params: {
  command: string;
  cwd?: string;
  env?: Record<string, string>;
  timeout?: number;
  skipApprovalCheck?: boolean;
}): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  const { command, cwd, env, timeout = 30_000, skipApprovalCheck = false } = params;
  const argv = isWindows ? ['cmd.exe', '/c', command] : ['/bin/sh', '-c', command];
  const [bin, ...args] = argv;

  if (!skipApprovalCheck) {
    const execMode = await getExecMode();
    if (execMode === 'remote-approve') {
      throw new Error('EXEC_NEED_REMOTE_APPROVAL');
    }
    if (execMode === 'blacklist') {
      const blocked = await isBlacklisted(bin, args);
      if (blocked) {
        throw new Error('Command in exec blacklist. Remove from exec-approvals.json or set mode to full');
      }
    }
  }

  return new Promise((resolve, reject) => {
    const proc = spawn(bin, args, {
      cwd: cwd || process.cwd(),
      env: { ...process.env, ...env },
    });
    let stdout = '';
    let stderr = '';
    proc.stdout?.on('data', (d) => { stdout += d.toString(); });
    proc.stderr?.on('data', (d) => { stderr += d.toString(); });
    const t = setTimeout(() => {
      proc.kill('SIGTERM');
      reject(new Error(`Command timed out after ${timeout}ms`));
    }, timeout);
    proc.on('close', (code, signal) => {
      clearTimeout(t);
      resolve({ stdout, stderr, exitCode: code ?? (signal ? -1 : 0) });
    });
    proc.on('error', (e) => {
      clearTimeout(t);
      reject(e);
    });
  });
}

function runSystemWhich(command: string): Promise<{ path: string }> {
  const which = platform() === 'win32' ? 'where' : 'which';
  return runSystemRun({ command: `${which} ${command}` }).then((r) => ({
    path: r.stdout.trim().split('\n')[0] ?? '',
  }));
}

/** 文件操作允许的根目录，防止路径穿越 */
function getFileOpRoot(): string {
  const root = process.env.APEXPANDA_WORKSPACE ?? process.cwd();
  return resolve(root);
}

function assertPathSafe(absolutePath: string): void {
  const root = getFileOpRoot();
  const normalized = resolve(absolutePath);
  if (!normalized.startsWith(root)) {
    throw new Error(`路径必须在 ${root} 内`);
  }
}

/** 将 path 解析为绝对路径，相对路径以 workspace 为基准 */
function resolveFilePath(p: string): string {
  const root = getFileOpRoot();
  const trimmed = p.trim();
  if (!trimmed) throw new Error('path 必填');
  if (trimmed.startsWith('/') || (isWindows && /^[a-zA-Z]:[\\/]/.test(trimmed))) {
    return resolve(trimmed);
  }
  return join(root, trimmed);
}

async function handleSystemReadFile(params: Record<string, unknown>): Promise<{ content: string; encoding: string }> {
  const p = String(params.path ?? '').trim();
  if (!p) throw new Error('path 必填');
  const encoding = params.encoding === 'base64' ? 'base64' : 'utf8';
  const abs = resolveFilePath(p);
  assertPathSafe(abs);
  const enc = encoding === 'base64' ? 'base64' : 'utf-8';
  const content = await readFile(abs, enc);
  return {
    content: typeof content === 'string' ? content : (content as Buffer).toString('base64'),
    encoding: enc,
  };
}

async function handleSystemWriteFile(params: Record<string, unknown>): Promise<{ ok: boolean }> {
  const pathParam = String(params.path ?? '').trim();
  const content = String(params.content ?? '');
  if (!pathParam) throw new Error('path 必填');
  const encoding = params.encoding === 'base64' ? 'base64' : 'utf8';
  const abs = resolveFilePath(pathParam);
  assertPathSafe(abs);
  await mkdir(dirname(abs), { recursive: true });
  const enc = encoding === 'base64' ? 'base64' : 'utf-8';
  await writeFile(abs, content, enc);
  return { ok: true };
}

async function handleSystemListDir(params: Record<string, unknown>): Promise<{ entries: Array<{ name: string; isDir: boolean; size?: number }> }> {
  const pathParam = String(params.path ?? '').trim();
  if (!pathParam) throw new Error('path 必填');
  const abs = resolveFilePath(pathParam);
  assertPathSafe(abs);
  const items = await readdir(abs, { withFileTypes: true });
  const entries = await Promise.all(
    items.map(async (d) => {
      let size: number | undefined;
      if (d.isFile()) {
        try {
          const st = await stat(join(abs, d.name));
          size = st.size;
        } catch {
          /* ignore */
        }
      }
      return { name: d.name, isDir: d.isDirectory(), size };
    })
  );
  return { entries };
}

function handleSystemClipboardRead(): { content: string } {
  const content = clipboard.readText();
  return { content: content ?? '' };
}

function handleSystemClipboardWrite(params: Record<string, unknown>): { ok: boolean } {
  const content = String(params.content ?? '');
  clipboard.writeText(content);
  return { ok: true };
}

function handleSystemProcessList(): { stdout: string } {
  const cmd = isWindows ? 'tasklist' : 'ps aux';
  const out = execSync(cmd, { encoding: 'utf-8', timeout: 10_000, maxBuffer: 2 * 1024 * 1024 });
  return { stdout: out };
}

function handleSystemProcessKill(params: Record<string, unknown>): { ok: boolean } {
  const pid = Number(params.pid);
  if (!Number.isInteger(pid) || pid <= 0) throw new Error('无效的 pid');
  const signal = params.signal === 'SIGKILL' ? 'SIGKILL' : 'SIGTERM';
  if (isWindows) {
    execSync(`taskkill /F /PID ${pid}`, { encoding: 'utf-8', timeout: 5_000 });
  } else {
    const sig = signal === 'SIGKILL' ? '-9' : '-15';
    execSync(`kill ${sig} ${pid}`, { encoding: 'utf-8', timeout: 5_000 });
  }
  return { ok: true };
}

let mainWindow: BrowserWindow | null = null;
let canvasWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let ws: WebSocket | null = null;
let nodeId: string | null = null;

/** 连接参数，用于断开后重连 */
let connectionParams: { gatewayUrl: string; deviceId: string; displayName: string; token?: string } | null = null;
let userDisconnected = false;
let lastStatus = 'DISCONNECTED';

function notifyStatus(status: string): void {
  lastStatus = status;
  if (process.env.NODE_ENV !== 'production') {
    console.log('[apexpanda-desktop] notifyStatus:', status);
  }
  if (mainWindow && !mainWindow.isDestroyed() && mainWindow.webContents && !mainWindow.webContents.isDestroyed()) {
    mainWindow.webContents.send('main:statusUpdate', status);
  }
}
let reconnectAttempts = 0;
let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;

function createCaptureWindow(preloadPath: string): BrowserWindow {
  return new BrowserWindow({
    width: 640,
    height: 480,
    show: false,
    icon: WINDOW_ICON_PATH,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: preloadPath,
    },
  });
}

async function createCameraWindow(): Promise<BrowserWindow> {
  const win = createCaptureWindow(join(__dirname, 'preload.js'));
  await win.loadFile(join(__dirname, '..', 'renderer', 'camera.html'));
  return win;
}

async function createMediaWindow(page: 'camera-clip' | 'screen-record', params: Record<string, unknown>): Promise<BrowserWindow> {
  const win = createCaptureWindow(join(__dirname, 'preload.js'));
  await win.loadFile(join(__dirname, '..', 'renderer', `${page}.html`));
  win.webContents.on('did-finish-load', () => {
    win.webContents.send('media-start', params);
  });
  return win;
}

async function handleCameraSnap(_params: Record<string, unknown>): Promise<Record<string, unknown>> {
  const win = await createCameraWindow();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      win.destroy();
      reject(new Error('camera.snap timeout'));
    }, 15_000);
    ipcMain.once('camera-snap-result', (_, result: { ok: boolean; base64?: string; error?: string }) => {
      clearTimeout(timeout);
      win.destroy();
      if (result.ok && result.base64) {
        resolve({ ok: true, base64: result.base64, format: 'jpg', ext: 'jpg' });
      } else {
        reject(new Error(result.error ?? 'camera failed'));
      }
    });
    win.webContents.on('did-finish-load', () => {
      win.webContents.send('camera-snap-start', {});
    });
  });
}

async function handleMediaRecord(
  page: 'camera-clip' | 'screen-record',
  params: Record<string, unknown>,
  timeoutMs: number
): Promise<Record<string, unknown>> {
  const duration = Math.min(60, Math.max(1, Number(params.duration ?? 5)));
  const win = await createMediaWindow(page, params);
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      win.destroy();
      reject(new Error(`${page} timeout`));
    }, timeoutMs);
    ipcMain.once('media-result', (_, result: { ok: boolean; base64?: string; ext?: string; error?: string }) => {
      clearTimeout(timeout);
      win.destroy();
      if (result.ok && result.base64) {
        resolve({ ok: true, base64: result.base64, format: result.ext ?? 'webm', ext: result.ext ?? 'webm' });
      } else {
        reject(new Error(result.error ?? 'record failed'));
      }
    });
  });
}

async function getOrCreateCanvasWindow(): Promise<BrowserWindow> {
  if (canvasWindow && !canvasWindow.isDestroyed()) return canvasWindow;
  canvasWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    show: false,
    icon: WINDOW_ICON_PATH,
    webPreferences: { nodeIntegration: false, contextIsolation: true },
  });
  await canvasWindow.loadURL('about:blank');
  return canvasWindow;
}

async function handleCanvasNavigate(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  const url = String(params.url ?? '').trim();
  if (!url) return { ok: false, error: 'url required' };
  const win = await getOrCreateCanvasWindow();
  await win.loadURL(url.startsWith('http') ? url : `https://${url}`);
  return { ok: true };
}

async function handleCanvasSnapshot(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  const win = await getOrCreateCanvasWindow();
  const fmt = (params.format as string) === 'png' ? 'png' : 'jpeg';
  const image = await win.webContents.capturePage();
  const buf = fmt === 'png' ? image.toPNG() : image.toJPEG(85);
  const base64 = buf.toString('base64');
  const ext = fmt === 'png' ? 'png' : 'jpg';
  return { ok: true, base64, format: ext, ext, width: image.getSize().width, height: image.getSize().height };
}

async function handleScreenOcr(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  const maxWidth = Math.min(1920, Math.max(640, Number(params.maxWidth) || 1080));
  const includeBase64 = Boolean(params.includeBase64);
  const sources = await desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: { width: maxWidth, height: Math.round(maxWidth * (9 / 16)) },
  });
  const src = sources.find((s) => s.id.startsWith('screen')) ?? sources[0];
  if (!src?.thumbnail) throw new Error('无法获取屏幕截图画布');
  const pngBuf = src.thumbnail.toPNG();
  const { createWorker } = await import('tesseract.js');
  const worker = await createWorker('chi_sim+eng', 3);
  try {
    const ret = await worker.recognize(pngBuf);
    const data = ret.data as { text?: string; words?: Array<{ text: string; bbox?: { x0: number; y0: number; x1: number; y1: number } }> };
    const words = data.words ?? [];
    const items = words.map((w) => {
      const b = w.bbox ?? { x0: 0, y0: 0, x1: 0, y1: 0 };
      return {
        text: w.text,
        x: Math.round(b.x0),
        y: Math.round(b.y0),
        width: Math.round(b.x1 - b.x0),
        height: Math.round(b.y1 - b.y0),
      };
    });
    const out: Record<string, unknown> = {
      fullText: (data.text ?? '').trim(),
      items,
    };
    if (includeBase64) out.base64 = pngBuf.toString('base64');
    return out;
  } finally {
    await worker.terminate();
  }
}

/** Windows 桌面 UI 自动化：使用 PowerShell 调用 user32 模拟鼠标/键盘，无原生依赖 */
async function runPsScript(script: string): Promise<{ ok: boolean; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    const ps = spawn('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script], {
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 10_000,
    });
    let stdout = '';
    let stderr = '';
    ps.stdout?.on('data', (d: Buffer) => { stdout += d.toString(); });
    ps.stderr?.on('data', (d: Buffer) => { stderr += d.toString(); });
    ps.on('close', (code: number) => resolve({ ok: code === 0, stdout, stderr }));
    ps.on('error', () => resolve({ ok: false, stdout, stderr: 'powershell spawn failed' }));
  });
}

async function handleUiTap(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  if (!isWindows) return { error: 'ui.tap 仅 Windows 桌面节点支持' };
  const text = typeof params.text === 'string' ? params.text.trim() : '';
  const x = typeof params.x === 'number' ? params.x : undefined;
  const y = typeof params.y === 'number' ? params.y : undefined;

  let targetX: number;
  let targetY: number;
  if (x != null && y != null) {
    targetX = Math.round(x);
    targetY = Math.round(y);
  } else if (text) {
    const ocr = await handleScreenOcr({ maxWidth: 1920 });
    const items = (ocr.items ?? []) as Array<{ text: string; x: number; y: number; width: number; height: number }>;
    const match = items.find((it) => it.text && it.text.includes(text));
    if (!match) return { error: `未在屏幕上找到文字「${text}」` };
    const disp = screen.getPrimaryDisplay();
    const captW = 1920;
    const captH = Math.round(1920 * (9 / 16));
    const screenW = disp.size.width;
    const screenH = disp.size.height;
    const scaleX = screenW / captW;
    const scaleY = screenH / captH;
    targetX = Math.round((match.x + match.width / 2) * scaleX);
    targetY = Math.round((match.y + match.height / 2) * scaleY);
  } else {
    return { error: '请提供 text 或 (x, y) 坐标' };
  }

  const script = `
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Mouse {
  [DllImport("user32.dll")]
  public static extern void mouse_event(int dwFlags, int dx, int dy, int cButtons, int dwExtraInfo);
  public const int MOUSEEVENTF_LEFTDOWN = 0x02;
  public const int MOUSEEVENTF_LEFTUP = 0x04;
  [DllImport("user32.dll")]
  public static extern bool SetCursorPos(int x, int y);
  public static void Click(int x, int y) {
    SetCursorPos(x, y);
    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0);
    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0);
  }
}
"@
[Mouse]::Click(${targetX}, ${targetY})
`;
  const result = await runPsScript(script);
  if (!result.ok) return { error: `ui.tap 执行失败: ${result.stderr || result.stdout}` };
  return { ok: true, x: targetX, y: targetY };
}

async function handleUiInput(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  if (!isWindows) return { error: 'ui.input 仅 Windows 桌面节点支持' };
  const text = String(params.text ?? '').trim();
  if (!text) return { error: '请提供要输入的 text' };
  const b64 = Buffer.from(text, 'utf16le').toString('base64');
  const script = `
Add-Type -AssemblyName System.Windows.Forms;
$old = [System.Windows.Forms.Clipboard]::GetText();
$bytes = [System.Convert]::FromBase64String('${b64}');
$str = [System.Text.Encoding]::Unicode.GetString($bytes);
[System.Windows.Forms.Clipboard]::SetText($str);
[System.Windows.Forms.SendKeys]::SendWait('^v');
[System.Windows.Forms.Clipboard]::SetText($old);
`;
  const result = await runPsScript(script);
  if (!result.ok) return { error: `ui.input 执行失败: ${result.stderr || result.stdout}` };
  return { ok: true };
}

async function handleUiDump(params: Record<string, unknown>): Promise<Record<string, unknown>> {
  if (!isWindows) return { error: 'ui.dump 仅 Windows 桌面节点支持' };
  const ocr = await handleScreenOcr({ ...params, maxWidth: params.maxWidth ?? 1920 });
  const items = (ocr.items ?? []) as Array<{ text: string; x: number; y: number; width: number; height: number }>;
  const lines = items.map((it) => `[${it.x},${it.y}] ${it.width}x${it.height}: "${(it.text ?? '').trim()}"`);
  const tree = `屏幕 OCR 元素树（共 ${items.length} 项）:\n${lines.join('\n')}`;
  return { tree, items, fullText: ocr.fullText };
}

async function handleSystemRun(params: Record<string, unknown>, reqParams?: Record<string, unknown>): Promise<unknown> {
  const runParams = {
    command: String(params.command ?? ''),
    cwd: params.cwd as string | undefined,
    env: params.env as Record<string, string> | undefined,
    timeout: (params.timeout as number) ?? 30_000,
  };
  try {
    return await runSystemRun(runParams);
  } catch (e) {
    if (e instanceof Error && e.message === 'EXEC_NEED_REMOTE_APPROVAL' && nodeId) {
      const sock = ws;
      if (!sock || sock.readyState !== 1) throw e;
      const reqId = `exec-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const ac = reqParams?._approvalContext;
      const sessionId = ac && typeof ac === 'object' && ac !== null && 'sessionId' in ac
        ? String((ac as { sessionId?: unknown }).sessionId ?? '')
        : undefined;
      const approved = await new Promise<boolean>((resolve) => {
        const timer = setTimeout(() => {
          pendingExecApprovals.delete(reqId);
          resolve(false);
        }, EXEC_APPROVAL_TIMEOUT_MS);
        pendingExecApprovals.set(reqId, { resolve, timer });
        const payload: Record<string, unknown> = {
          reqId,
          nodeId,
          command: runParams.command,
          params: { cwd: runParams.cwd, env: runParams.env, timeout: runParams.timeout },
        };
        if (sessionId) payload.sessionId = sessionId;
        sock.send(JSON.stringify({ type: 'exec_approval_request', payload }));
      });
      if (!approved) throw new Error('Command rejected by admin');
      return await runSystemRun({ ...runParams, skipApprovalCheck: true });
    }
    throw e;
  }
}

async function handleCommand(command: string, params: Record<string, unknown>, reqParams?: Record<string, unknown>): Promise<unknown> {
  if (command === 'system.run') {
    return handleSystemRun(params, reqParams);
  }
  if (command === 'system.which') {
    return runSystemWhich(String(params.command ?? ''));
  }
  if (command === 'camera.snap') {
    return handleCameraSnap(params);
  }
  if (command === 'camera.clip') {
    const timeout = (Math.min(60, Math.max(1, Number(params.duration ?? 5))) + 10) * 1000;
    return handleMediaRecord('camera-clip', params, timeout);
  }
  if (command === 'screen.record') {
    const timeout = (Math.min(60, Math.max(1, Number(params.duration ?? 5))) + 10) * 1000;
    return handleMediaRecord('screen-record', params, timeout);
  }
  if (command === 'canvas.navigate') {
    return handleCanvasNavigate(params);
  }
  if (command === 'canvas.snapshot') {
    return handleCanvasSnapshot(params);
  }
  if (command === 'screen.ocr') {
    return handleScreenOcr(params);
  }
  if (command === 'system.readFile') {
    return handleSystemReadFile(params);
  }
  if (command === 'system.writeFile') {
    return handleSystemWriteFile(params);
  }
  if (command === 'system.listDir') {
    return handleSystemListDir(params);
  }
  if (command === 'system.clipboardRead') {
    return handleSystemClipboardRead();
  }
  if (command === 'system.clipboardWrite') {
    return handleSystemClipboardWrite(params);
  }
  if (command === 'system.processList') {
    return handleSystemProcessList();
  }
  if (command === 'system.processKill') {
    return handleSystemProcessKill(params);
  }
  if (command === 'location.get') {
    return { error: 'location.get 需移动端节点，敬请期待' };
  }
  if (command === 'ui.tap') {
    return handleUiTap(params);
  }
  if (command === 'ui.input') {
    return handleUiInput(params);
  }
  if (command === 'ui.dump') {
    return handleUiDump(params);
  }
  return { error: `Unknown command: ${command}` };
}

function sendRes(id: string, ok: boolean, payload: unknown): void {
  if (ws?.readyState === 1) {
    ws.send(JSON.stringify({ type: 'res', id, ok, payload }));
  }
}

function connect(gatewayUrl: string, deviceId: string, displayName: string, token?: string): void {
  if (ws) {
    ws.close();
    ws = null;
  }
  userDisconnected = false;
  connectionParams = { gatewayUrl, deviceId, displayName, token };
  const wsUrl = parseGatewayWsUrl(gatewayUrl);
  const isRetry = reconnectAttempts > 0;
  notifyStatus(isRetry ? 'CONNECT_FAILED' : 'CONNECTING');
  if (!isRetry) {
    console.log('[apexpanda-desktop] Connecting to', wsUrl);
  }
  ws = new WebSocket(wsUrl);
  const thisWs = ws;

  let openTimeoutId: ReturnType<typeof setTimeout> | null = setTimeout(() => {
    openTimeoutId = null;
    if (thisWs.readyState === 0) {
      console.warn('[apexpanda-desktop] Connection timeout: Gateway may not be running');
      thisWs.close();
    }
  }, 8_000);

  let connectResponseTimer: ReturnType<typeof setTimeout> | null = null;
  const clearConnectTimer = () => {
    if (connectResponseTimer) {
      clearTimeout(connectResponseTimer);
      connectResponseTimer = null;
    }
  };

  ws.on('open', () => {
    if (openTimeoutId) {
      clearTimeout(openTimeoutId);
      openTimeoutId = null;
    }
    reconnectAttempts = 0;
    connectResponseTimer = setTimeout(() => {
      connectResponseTimer = null;
      if (thisWs.readyState === 1) {
        console.warn('[apexpanda-desktop] Connect timeout: Gateway did not respond');
        thisWs.close();
      }
    }, 12_000);
    thisWs.send(
      JSON.stringify({
        type: 'connect',
        payload: {
          role: 'node',
          deviceId,
          token,
          displayName,
          platform: 'desktop',
          protocolVersion: '1',
          capabilities: CAPABILITIES,
        },
      })
    );
  });

  ws.on('message', async (data: Buffer | string) => {
    try {
      const frame = JSON.parse(data.toString()) as Record<string, unknown>;
      const type = String(frame.type ?? '');

      if (type === 'ping') {
        if (thisWs.readyState === 1) thisWs.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
        return;
      }

      if (type === 'connect_result') {
        clearConnectTimer();
        const ok = Boolean(frame.ok);
        const nid = frame.nodeId != null ? String(frame.nodeId) : null;
        const errMsg = frame.error != null ? String(frame.error) : '';
        console.log('[apexpanda-desktop] connect_result received:', { ok, nid, err: errMsg });
        if (ok && nid) {
          nodeId = nid;
          notifyStatus('CONNECTED');
          loadExecApprovals().then((a) => {
            if (ws?.readyState === 1) {
              ws.send(JSON.stringify({ type: 'exec_approvals_report', payload: a }));
            }
          }).catch(() => {});
        } else {
          if (errMsg) console.log('[apexpanda-desktop] connect_result:', errMsg);
          notifyStatus('PENDING_PAIRING');
        }
        return;
      }

      if (type === 'exec_approvals_update') {
        const payload = frame.payload as { mode?: string; rules?: unknown[] } | undefined;
        if (payload && typeof payload === 'object') {
          saveExecApprovals(payload as ExecApprovals).catch((e) => console.error('[apexpanda-desktop] saveExecApprovals:', e));
        }
        return;
      }

      if (type === 'exec_approval_result') {
        const payload = (frame.payload as Record<string, unknown>) ?? {};
        const reqId = String(payload.reqId ?? '');
        const approved = Boolean(payload.approved);
        const p = pendingExecApprovals.get(reqId);
        if (p) {
          clearTimeout(p.timer);
          pendingExecApprovals.delete(reqId);
          p.resolve(approved);
        }
        return;
      }

      if (type === 'paired') {
        clearConnectTimer();
        const nid = String(frame.nodeId ?? '');
        const tok = String(frame.token ?? '');
        if (nid && tok) {
          nodeId = nid;
          if (connectionParams) connectionParams = { ...connectionParams, token: tok };
          const cfg = await loadNodeConfig();
          await saveNodeConfig({ ...cfg, token: tok });
          notifyStatus('CONNECTED');
          loadExecApprovals().then((a) => {
            if (ws?.readyState === 1) {
              ws.send(JSON.stringify({ type: 'exec_approvals_report', payload: a }));
            }
          }).catch(() => {});
          ws?.send(
            JSON.stringify({
              type: 'connect',
              payload: {
                role: 'node',
                deviceId,
                token: tok,
                displayName,
                platform: 'desktop',
                protocolVersion: '1',
                capabilities: CAPABILITIES,
              },
            })
          );
        }
        return;
      }

      if (type === 'req' && frame.method === 'node.invoke') {
        const id = String(frame.id ?? '');
        const reqParams = (frame.params as Record<string, unknown>) ?? {};
        const command = String(reqParams.command ?? '');
        const params = (reqParams.params as Record<string, unknown>) ?? {};

        try {
          const result = await handleCommand(command, params, reqParams);
          sendRes(id, true, result);
        } catch (e) {
          sendRes(id, false, { error: e instanceof Error ? e.message : String(e) });
        }
      }
    } catch {
      /* ignore */
    }
  });

  ws.on('close', () => {
    if (openTimeoutId) {
      clearTimeout(openTimeoutId);
      openTimeoutId = null;
    }
    clearConnectTimer();
    if (ws === thisWs) ws = null;
    if (userDisconnected) {
      notifyStatus('DISCONNECTED');
      return;
    }
    const params = connectionParams;
    if (!params) return;
    const delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts), RECONNECT_MAX_MS);
    reconnectAttempts++;
    console.log('[apexpanda-desktop] Disconnected, retrying in', delay, 'ms');
    notifyStatus('CONNECT_FAILED');
    if (reconnectTimeoutId) clearTimeout(reconnectTimeoutId);
    reconnectTimeoutId = setTimeout(() => {
      reconnectTimeoutId = null;
      connect(params.gatewayUrl, params.deviceId, params.displayName, params.token);
    }, delay);
  });

  ws.on('error', (e) => {
    if (openTimeoutId) {
      clearTimeout(openTimeoutId);
      openTimeoutId = null;
    }
    clearConnectTimer();
    console.error('[apexpanda-desktop] WebSocket error:', e.message);
  });
}

async function ensureExecApprovalsFile(): Promise<void> {
  const path = join(getDataDir(), 'exec-approvals.json');
  try {
    await readFile(path, 'utf-8');
  } catch {
    const sample: ExecApprovals = {
      mode: 'full',
      rules: [],
    };
    await saveExecApprovals(sample);
    console.log('[apexpanda-desktop] Created exec-approvals.json');
  }
}

function setupIpcHandlers(): void {
  ipcMain.on('main:rendererReady', () => {
    if (mainWindow && !mainWindow.isDestroyed() && mainWindow.webContents && !mainWindow.webContents.isDestroyed()) {
      mainWindow.webContents.send('main:statusUpdate', lastStatus);
    }
  });
  ipcMain.handle('main:getCurrentStatus', () => lastStatus);

  ipcMain.handle('main:getConfig', async () => {
    const cfg = await loadNodeConfig();
    return {
      gatewayUrl: cfg.gatewayUrl ?? process.env.APEXPANDA_GATEWAY_URL ?? 'http://127.0.0.1:18790',
      displayName: cfg.displayName ?? process.env.APEXPANDA_NODE_DISPLAY_NAME ?? 'Desktop Node',
    };
  });

  ipcMain.handle('main:doConnect', async (_ev, gatewayUrl: string, displayName: string) => {
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }
    reconnectAttempts = 0;
    const cfg = await loadNodeConfig();
    if (!cfg.deviceId) {
      const { randomUUID } = await import('node:crypto');
      cfg.deviceId = randomUUID();
      await saveNodeConfig(cfg);
    }
    const url = (gatewayUrl ?? '').trim();
    const name = (displayName ?? '').trim() || 'Desktop Node';
    await saveNodeConfig({ ...cfg, gatewayUrl: url, displayName: name });
    const token = process.env.APEXPANDA_NODE_TOKEN ?? cfg.token;
    connect(url, cfg.deviceId!, name, token);
  });

  ipcMain.handle('main:doDisconnect', () => {
    userDisconnected = true;
    connectionParams = null;
    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }
    notifyStatus('DISCONNECTED');
    if (ws) {
      try { ws.close(); } catch { /* ignore */ }
      ws = null;
    }
  });

  ipcMain.handle('main:getLaunchAtStartup', async () => {
    const cfg = await loadNodeConfig();
    return !!cfg.launchAtStartup;
  });

  ipcMain.handle('main:setLaunchAtStartup', async (_ev, enabled: boolean) => {
    const cfg = await loadNodeConfig();
    await saveNodeConfig({ ...cfg, launchAtStartup: enabled });
    app.setLoginItemSettings({ openAtLogin: enabled });
  });

  ipcMain.on('main:windowMinimize', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.minimize();
  });
  ipcMain.on('main:windowClose', () => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.hide();
  });
}

async function main(): Promise<void> {
  if (process.env.NODE_ENV !== 'production') {
    app.commandLine.appendSwitch('ignore-certificate-errors');
  }
  await app.whenReady();
  await ensureExecApprovalsFile();

  let cfg = await loadNodeConfig();
  const { randomUUID } = await import('node:crypto');
  if (!cfg.deviceId) {
    cfg = { ...cfg, deviceId: randomUUID() };
    await saveNodeConfig(cfg);
  }

  if (cfg.launchAtStartup) {
    app.setLoginItemSettings({ openAtLogin: true });
  }

  setupIpcHandlers();

  const trayIconPath = platform() === 'win32' ? join(ASSETS_DIR, 'icon.ico') : join(ASSETS_DIR, 'icon.png');
  let trayIcon = nativeImage.createEmpty();
  try {
    trayIcon = nativeImage.createFromPath(trayIconPath);
  } catch {
    trayIcon = nativeImage.createEmpty();
  }
  if (trayIcon.isEmpty()) trayIcon = nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==');

  tray = new Tray(trayIcon);
  tray.setToolTip('ApexPanda 桌面节点');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: '显示', click: () => mainWindow?.show() },
      { label: '退出', click: () => app.quit() },
    ])
  );

  const preloadMainPath = join(__dirname, 'preload-main.js');
  mainWindow = new BrowserWindow({
    width: 420,
    height: 600,
    show: false,
    frame: true,
    icon: WINDOW_ICON_PATH,
    webPreferences: { nodeIntegration: false, contextIsolation: true, preload: preloadMainPath },
  });
  const gatewayUrl = cfg.gatewayUrl ?? process.env.APEXPANDA_GATEWAY_URL ?? 'http://127.0.0.1:18790';
  const displayName = cfg.displayName ?? process.env.APEXPANDA_NODE_DISPLAY_NAME ?? 'Desktop Node';
  mainWindow.webContents.once('did-finish-load', () => {
    if (cfg.launchAtStartup && cfg.gatewayUrl && cfg.deviceId) {
      const token = process.env.APEXPANDA_NODE_TOKEN ?? cfg.token;
      connect(gatewayUrl, cfg.deviceId!, displayName, token);
    } else {
      notifyStatus('DISCONNECTED');
    }
  });
  await mainWindow.loadFile(join(__dirname, '..', 'renderer', 'index.html'));
  mainWindow.show();
  let isQuitting = false;
  mainWindow.on('show', () => {
    const win = mainWindow;
    if (win && !win.isDestroyed() && win.webContents && !win.webContents.isDestroyed()) {
      win.webContents.send('main:statusUpdate', lastStatus);
    }
  });
  mainWindow.on('close', (e) => {
    if (!isQuitting) {
      e.preventDefault();
      mainWindow?.hide();
    }
  });

  app.on('window-all-closed', () => {});
  app.on('before-quit', () => {
    isQuitting = true;
    ws?.close();
  });
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
