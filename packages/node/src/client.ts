/**
 * WebSocket 客户端：连接 Gateway，处理 node.invoke 请求
 */
import { WebSocket } from 'ws';
import { runSystemRun, runSystemWhich, runSystemReadFile, runSystemWriteFile, runSystemListDir, runSystemClipboardRead, runSystemClipboardWrite, runSystemProcessList, runSystemProcessKill, getExecApprovalsForReport, saveExecApprovals } from './executor.js';
import { detectEnvTools } from './capabilities.js';

const RECONNECT_BASE_MS = 1000;
const EXEC_APPROVAL_TIMEOUT_MS = 30_000;

const pendingExecApprovals = new Map<string, { resolve: (approved: boolean) => void; timer: ReturnType<typeof setTimeout> }>();
const RECONNECT_MAX_MS = 60_000;

export interface NodeClientOptions {
  gatewayUrl: string;
  token?: string;
  deviceId: string;
  displayName: string;
  platform: string;
  onPaired?: (nodeId: string, token: string) => void | Promise<void>;
}

function parseGatewayWsUrl(httpUrl: string): string {
  const u = httpUrl.replace(/^http:\/\//, 'ws://').replace(/^https:\/\//, 'wss://');
  if (!u.startsWith('ws')) return `ws://${httpUrl}`;
  const url = new URL(u);
  url.pathname = url.pathname.replace(/\/$/, '') || '/';
  if (!url.pathname.endsWith('/ws')) {
    url.pathname = url.pathname + (url.pathname.endsWith('/') ? '' : '/') + 'ws';
  }
  url.searchParams.set('role', 'node');
  return url.toString();
}

export function createNodeClient(opts: NodeClientOptions) {
  const wsUrl = parseGatewayWsUrl(opts.gatewayUrl);
  let ws: WebSocket | null = null;
  let nodeId: string | null = null;
  let reconnectAttempts = 0;
  let optsRef = { ...opts };

  const connect = () => {
    ws = new WebSocket(wsUrl);

    ws.on('open', () => {
      reconnectAttempts = 0;
      const envTools = detectEnvTools();
      const tagsEnv = process.env.APEXPANDA_NODE_TAGS ?? '';
      const tags = tagsEnv ? tagsEnv.split(',').map((t) => t.trim()).filter(Boolean) : [];
      ws!.send(
        JSON.stringify({
          type: 'connect',
          payload: {
            role: 'node',
            deviceId: optsRef.deviceId,
            token: optsRef.token,
            displayName: optsRef.displayName,
            platform: optsRef.platform,
            protocolVersion: '1',
            capabilities: ['system.run', 'system.which', 'system.readFile', 'system.writeFile', 'system.listDir', 'system.clipboardRead', 'system.clipboardWrite', 'system.processList', 'system.processKill'],
            envTools,
            tags,
          },
        })
      );
    });

    ws.on('message', async (data: Buffer | string) => {
      try {
        const frame = JSON.parse(data.toString()) as Record<string, unknown>;
        const type = String(frame.type ?? '');

        if (type === 'ping') {
          ws?.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
          return;
        }

        if (type === 'connect_result') {
          const ok = Boolean(frame.ok);
          const nid = frame.nodeId != null ? String(frame.nodeId) : null;
          if (ok && nid) {
            nodeId = nid;
            getExecApprovalsForReport().then((approvals) => {
              if (ws?.readyState === 1) {
                ws.send(JSON.stringify({ type: 'exec_approvals_report', payload: approvals }));
              }
            }).catch(() => {});
          }
          return;
        }

        if (type === 'exec_approvals_update') {
          const payload = frame.payload as { mode?: string; rules?: unknown[] } | undefined;
          if (payload && typeof payload === 'object') {
            saveExecApprovals(payload).catch((e) =>
              console.error('[apexpanda-node] saveExecApprovals error:', e)
            );
          }
          return;
        }

        if (type === 'paired') {
          const nid = String(frame.nodeId ?? '');
          const token = String(frame.token ?? '');
          if (nid && token) {
            nodeId = nid;
            optsRef.token = token;
            if (optsRef.onPaired) {
              await optsRef.onPaired(nid, token);
            }
            getExecApprovalsForReport().then((approvals) => {
              if (ws?.readyState === 1) {
                ws.send(JSON.stringify({ type: 'exec_approvals_report', payload: approvals }));
              }
            }).catch(() => {});
            const envTools = detectEnvTools();
            const tagsEnv = process.env.APEXPANDA_NODE_TAGS ?? '';
            const tags = tagsEnv ? tagsEnv.split(',').map((t) => t.trim()).filter(Boolean) : [];
            ws?.send(
              JSON.stringify({
                type: 'connect',
                payload: {
                  role: 'node',
                  deviceId: optsRef.deviceId,
                  token: optsRef.token,
                  displayName: optsRef.displayName,
                  platform: optsRef.platform,
                  protocolVersion: '1',
                  capabilities: ['system.run', 'system.which', 'system.readFile', 'system.writeFile', 'system.listDir', 'system.clipboardRead', 'system.clipboardWrite', 'system.processList', 'system.processKill'],
                  envTools,
                  tags,
                },
              })
            );
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

        if (type === 'req' && frame.method === 'node.invoke') {
          const id = String(frame.id ?? '');
          const params = (frame.params as Record<string, unknown>) ?? {};
          const command = String(params.command ?? '');
          const cmdParams = (params.params as Record<string, unknown>) ?? {};

          const runSystemRunWithApproval = async (): Promise<unknown> => {
            if (command === 'system.run') {
              const runParams = {
                command: String(cmdParams.command ?? ''),
                cwd: cmdParams.cwd as string | undefined,
                env: cmdParams.env as Record<string, string> | undefined,
                timeout: (cmdParams.timeout as number) ?? 30_000,
                onChunk: (stream: 'stdout' | 'stderr', chunk: string) => {
                  if (ws?.readyState === 1 && chunk) {
                    ws.send(JSON.stringify({ type: 'res_stream_chunk', id, stream, chunk }));
                  }
                },
              };
              try {
                return await runSystemRun(runParams);
              } catch (e) {
                if (e instanceof Error && e.message === 'EXEC_NEED_REMOTE_APPROVAL' && nodeId) {
                  const sock = ws;
                  if (!sock) throw new Error('WebSocket disconnected');
                  const reqId = `exec-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
                  const ac = params._approvalContext;
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
                  return await runSystemRun({ ...runParams, skipApprovalCheck: true, onChunk: runParams.onChunk });
                }
                throw e;
              }
            }
            if (command === 'system.which') {
              return await runSystemWhich(String(cmdParams.command ?? ''));
            }
            return { error: `Unknown command: ${command}` };
          };

          try {
            let result: unknown;
            if (command === 'system.run') {
              result = await runSystemRunWithApproval();
            } else if (command === 'system.which') {
              result = await runSystemWhich(String(cmdParams.command ?? ''));
            } else if (command === 'system.readFile') {
              result = await runSystemReadFile({
                path: String(cmdParams.path ?? ''),
                encoding: cmdParams.encoding as string | undefined,
              });
            } else if (command === 'system.writeFile') {
              result = await runSystemWriteFile({
                path: String(cmdParams.path ?? ''),
                content: String(cmdParams.content ?? ''),
                encoding: cmdParams.encoding as string | undefined,
              });
            } else if (command === 'system.listDir') {
              result = await runSystemListDir({ path: String(cmdParams.path ?? '') });
            } else if (command === 'system.clipboardRead') {
              result = await runSystemClipboardRead();
            } else if (command === 'system.clipboardWrite') {
              result = await runSystemClipboardWrite({ content: String(cmdParams.content ?? '') });
            } else if (command === 'system.processList') {
              result = await runSystemProcessList();
            } else if (command === 'system.processKill') {
              result = await runSystemProcessKill({
                pid: Number(cmdParams.pid),
                signal: cmdParams.signal as string | undefined,
              });
            } else {
              result = { error: `Unknown command: ${command}` };
              ws?.send(JSON.stringify({ type: 'res', id, ok: false, payload: result }));
              return;
            }
            ws?.send(JSON.stringify({ type: 'res', id, ok: true, payload: result }));
          } catch (e) {
            ws?.send(
              JSON.stringify({
                type: 'res',
                id,
                ok: false,
                payload: { error: e instanceof Error ? e.message : String(e) },
              })
            );
          }
        }
      } catch {
        /* ignore parse errors */
      }
    });

    ws.on('close', () => {
      ws = null;
      scheduleReconnect();
    });

    ws.on('error', (e) => {
      console.error('[apexpanda-node] WebSocket error:', e.message);
    });
  };

  function scheduleReconnect() {
    const delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts), RECONNECT_MAX_MS);
    reconnectAttempts++;
    console.log(`[apexpanda-node] Reconnecting in ${delay}ms (attempt ${reconnectAttempts})...`);
    setTimeout(connect, delay);
  }

  connect();
  return {
    close: () => {
      if (ws) {
        ws.close();
        ws = null;
      }
    },
  };
}
