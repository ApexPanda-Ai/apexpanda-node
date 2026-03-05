/**
 * Preload script for camera/screen capture windows
 */
import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('electronAPI', {
  onCameraSnapStart: (cb: (params: Record<string, unknown>) => void) => {
    ipcRenderer.on('camera-snap-start', (_ev, params: Record<string, unknown>) => cb(params ?? {}));
  },
  sendCameraSnapResult: (result: { ok: boolean; base64?: string; error?: string }) => {
    ipcRenderer.send('camera-snap-result', result);
  },
  onMediaStart: (cb: (params: Record<string, unknown>) => void) => {
    ipcRenderer.on('media-start', (_ev, params: Record<string, unknown>) => cb(params ?? {}));
  },
  sendMediaResult: (result: { ok: boolean; base64?: string; ext?: string; format?: string; error?: string }) => {
    ipcRenderer.send('media-result', result);
  },
});
