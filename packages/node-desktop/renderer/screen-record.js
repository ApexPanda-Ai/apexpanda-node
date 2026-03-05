/**
 * Screen record - getDisplayMedia + MediaRecorder
 */
async function record(params = {}) {
  try {
    const duration = Math.min(60, Math.max(1, Number(params.duration ?? 5)));
    const noAudio = Boolean(params.noAudio ?? true);

    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: { displaySurface: 'monitor', width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: !noAudio,
    });

    const video = document.getElementById('video');
    video.srcObject = stream;
    await video.play();

    const recorder = new MediaRecorder(stream, {
      mimeType: MediaRecorder.isTypeSupported('video/webm;codecs=vp9') ? 'video/webm;codecs=vp9' : 'video/webm',
      videoBitsPerSecond: 2500000,
    });
    const chunks = [];
    recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    recorder.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      const blob = new Blob(chunks, { type: recorder.mimeType });
      const reader = new FileReader();
      reader.onloadend = () => {
        const base64 = (reader.result ?? '').toString().split(',')[1];
        window.electronAPI?.sendMediaResult?.({ ok: true, base64, ext: 'webm', format: 'webm' });
      };
      reader.readAsDataURL(blob);
    };
    recorder.start(1000);
    setTimeout(() => recorder.stop(), duration * 1000);
  } catch (e) {
    window.electronAPI?.sendMediaResult?.({
      ok: false,
      error: e instanceof Error ? e.message : String(e),
    });
  }
}

window.electronAPI?.onMediaStart?.((p) => record(p));
