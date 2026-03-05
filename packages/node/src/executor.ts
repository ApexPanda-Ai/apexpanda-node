/**
 * 本地命令执行：system.run / system.which / system.readFile / system.writeFile / system.listDir /
 * system.clipboardRead / system.clipboardWrite / system.processList / system.processKill
 */
import { spawn, execSync } from 'node:child_process';
import { readFile, writeFile, mkdir, readdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { platform } from 'node:os';
import clipboard from 'clipboardy';

const isWindows = platform() === 'win32';

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

function getApprovalsPath(): string {
  const base = process.env.APEXPANDA_DATA_DIR ?? join(process.env.HOME ?? process.env.USERPROFILE ?? process.cwd(), '.apexpanda');
  return join(base, 'exec-approvals.json');
}

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

async function loadApprovals(): Promise<ExecApprovals> {
  try {
    const raw = await readFile(getApprovalsPath(), 'utf-8');
    return JSON.parse(raw) as ExecApprovals;
  } catch {
    return { mode: 'full', rules: [] };
  }
}

/** 供 client 上报白名单到 Gateway */
export async function getExecApprovalsForReport(): Promise<ExecApprovals> {
  return loadApprovals();
}

/** 供 client 处理 Gateway 下发的白名单更新 */
export async function saveExecApprovals(data: { mode?: string; rules?: unknown[] }): Promise<void> {
  const { writeFile, mkdir } = await import('node:fs/promises');
  const path = getApprovalsPath();
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(data, null, 2), 'utf-8');
}

function matchRule(cmd: string, args: string[], rule: ApprovalRule): boolean {
  const fullCmd = isWindows && args?.length >= 2 ? args[1] : [cmd, ...(args ?? [])].join(' ');
  if (rule.command) {
    const cmdNorm = isWindows ? cmd.replace(/\\/g, '/') : cmd;
    const ruleNorm = rule.command.replace(/\\/g, '/');
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
      const re = new RegExp(rule.pattern);
      return re.test(fullCmd);
    } catch {
      return false;
    }
  }
  return false;
}

/** 是否在黑名单中（mode=blacklist 时，命中 rules 则禁止执行） */
async function isBlacklisted(command: string, args: string[]): Promise<boolean> {
  const approvals = await loadApprovals();
  if (approvals.mode !== 'blacklist' || !approvals.rules?.length) return false;
  return approvals.rules.some((r) => matchRule(command, args, r));
}

async function getExecMode(): Promise<'full' | 'blacklist' | 'remote-approve'> {
  const envMode = process.env.APEXPANDA_EXEC_MODE?.toLowerCase();
  if (envMode === 'remote-approve' || envMode === 'remote_approve') return 'remote-approve';
  if (envMode === 'blacklist') return 'blacklist';
  const cfg = await loadApprovals();
  const m = cfg.mode?.toLowerCase();
  if (m === 'blacklist' || m === 'remote-approve') return m;
  return 'full';  // full 或旧 allowlist 均视为全部允许
}

export type StreamChunkCallback = (stream: 'stdout' | 'stderr', chunk: string) => void;

export async function runSystemRun(params: {
  command: string;
  cwd?: string;
  env?: Record<string, string>;
  timeout?: number;
  /** 已通过远程审批时跳过 allowlist 检查 */
  skipApprovalCheck?: boolean;
  /** 可选：收到 stdout/stderr 时回调，用于流式上报 */
  onChunk?: StreamChunkCallback;
}): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  const { command, cwd, env, timeout = 30_000, skipApprovalCheck = false, onChunk } = params;

  const argv = isWindows
    ? ['cmd.exe', '/c', command]
    : ['/bin/sh', '-c', command];

  const [bin, ...args] = argv;
  if (!skipApprovalCheck) {
    const execMode = await getExecMode();
    if (execMode === 'remote-approve') {
      throw new Error('EXEC_NEED_REMOTE_APPROVAL');
    }
    if (execMode === 'blacklist') {
      const blocked = await isBlacklisted(bin, args);
      if (blocked) {
        throw new Error('Command in exec blacklist. Remove from ~/.apexpanda/exec-approvals.json or set mode to full');
      }
    }
  }

  return new Promise((resolve, reject) => {
    const proc = spawn(bin, args, {
      cwd: cwd || process.cwd(),
      env: { ...process.env, ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    proc.stdout?.on('data', (d) => {
      const s = d.toString();
      stdout += s;
      onChunk?.('stdout', s);
    });
    proc.stderr?.on('data', (d) => {
      const s = d.toString();
      stderr += s;
      onChunk?.('stderr', s);
    });

    const t = setTimeout(() => {
      proc.kill('SIGTERM');
      reject(new Error(`Command timed out after ${timeout}ms`));
    }, timeout);

    proc.on('close', (code, signal) => {
      clearTimeout(t);
      resolve({
        stdout,
        stderr,
        exitCode: code ?? (signal ? 128 : 0),
      });
    });
    proc.on('error', (e) => {
      clearTimeout(t);
      reject(e);
    });
  });
}

export async function runSystemWhich(command: string): Promise<{ path: string }> {
  const { execSync } = await import('node:child_process');
  const whichCmd = isWindows ? `where ${command}` : `which ${command}`;
  try {
    const out = execSync(whichCmd, { encoding: 'utf-8' });
    const path = out.trim().split(/\r?\n/)[0] ?? '';
    return { path };
  } catch {
    return { path: '' };
  }
}

export async function runSystemReadFile(params: { path: string; encoding?: string }): Promise<{ content: string; encoding: string }> {
  const { path: p, encoding = 'utf8' } = params;
  const abs = resolve(p);
  assertPathSafe(abs);
  const enc = encoding === 'base64' ? 'base64' : 'utf-8';
  const content = await readFile(abs, enc);
  return { content: typeof content === 'string' ? content : (content as Buffer).toString('base64'), encoding: enc };
}

export async function runSystemWriteFile(params: { path: string; content: string; encoding?: string }): Promise<{ ok: boolean }> {
  const { path: p, content, encoding = 'utf8' } = params;
  const abs = resolve(p);
  assertPathSafe(abs);
  await mkdir(dirname(abs), { recursive: true });
  const enc = encoding === 'base64' ? 'base64' : 'utf-8';
  await writeFile(abs, content, enc);
  return { ok: true };
}

export async function runSystemListDir(params: { path: string }): Promise<{ entries: Array<{ name: string; isDir: boolean; size?: number }> }> {
  const abs = resolve(params.path);
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

/** 剪贴板读取：需显示环境（X11/Wayland 或 Windows 桌面），headless 服务器可能不可用 */
export async function runSystemClipboardRead(): Promise<{ content: string }> {
  const content = await clipboard.read();
  return { content: content ?? '' };
}

/** 剪贴板写入 */
export async function runSystemClipboardWrite(params: { content: string }): Promise<{ ok: boolean }> {
  await clipboard.write(params.content);
  return { ok: true };
}

/** 进程列表：返回 ps/tasklist 原始输出 */
export async function runSystemProcessList(): Promise<{ stdout: string }> {
  const cmd = isWindows ? 'tasklist' : 'ps aux';
  const out = execSync(cmd, { encoding: 'utf-8', timeout: 10_000, maxBuffer: 2 * 1024 * 1024 });
  return { stdout: out };
}

/** 进程终止 */
export async function runSystemProcessKill(params: { pid: number; signal?: string }): Promise<{ ok: boolean }> {
  const { pid, signal = 'SIGTERM' } = params;
  if (!Number.isInteger(pid) || pid <= 0) throw new Error('无效的 pid');
  if (isWindows) {
    execSync(`taskkill /F /PID ${pid}`, { encoding: 'utf-8', timeout: 5_000 });
  } else {
    const sig = signal === 'SIGKILL' ? '-9' : '-15';
    execSync(`kill ${sig} ${pid}`, { encoding: 'utf-8', timeout: 5_000 });
  }
  return { ok: true };
}
