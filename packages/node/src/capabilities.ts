/**
 * 节点能力发现：检测本机已安装工具，上报 Gateway 供 Dashboard 展示及选节点参考
 */
import { execSync } from 'node:child_process';
import { platform } from 'node:os';

const isWindows = platform() === 'win32';

const TOOLS_TO_CHECK = ['adb', 'docker', 'node', 'npm', 'python', 'python3', 'git', 'java'];

/** 检测单个工具是否可用 */
function checkTool(name: string): boolean {
  try {
    const cmd = isWindows ? `where ${name}` : `command -v ${name} || which ${name}`;
    execSync(cmd, { encoding: 'utf-8', timeout: 2000, stdio: ['ignore', 'pipe', 'pipe'] });
    return true;
  } catch {
    return false;
  }
}

/** 检测本机已安装的环境工具，返回工具名列表 */
export function detectEnvTools(): string[] {
  const found: string[] = [];
  for (const name of TOOLS_TO_CHECK) {
    if (checkTool(name)) found.push(name);
  }
  // python3 和 python 去重（若两者都在，只保留一个）
  if (found.includes('python') && found.includes('python3')) {
    const idx = found.indexOf('python');
    if (idx >= 0) found.splice(idx, 1);
  }
  return found;
}
