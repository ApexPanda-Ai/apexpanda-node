/**
 * Camera capture - getUserMedia + canvas to base64
 * facing: front->user, back->environment
 */
const video = document.getElementById('video');
const canvas = document.getElementById('canvas');

async function capture(params = {}) {
  try {
    const facing = params.facing === 'back' ? 'environment' : 'user';
    const maxW = Math.min(2048, Math.max(320, Number(params.maxWidth ?? 1200)));
    const quality = Math.min(1, Math.max(0.1, Number(params.quality ?? 0.9)));

    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: facing, width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false,
    });
    video.srcObject = stream;
    await video.play();
    await new Promise((r) => setTimeout(r, 500));

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(video, 0, 0);
    stream.getTracks().forEach((t) => t.stop());

    let w = canvas.width,
      h = canvas.height;
    if (w > maxW) {
      h = (h * maxW) / w;
      w = maxW;
    }
    const c2 = document.createElement('canvas');
    c2.width = w;
    c2.height = h;
    c2.getContext('2d').drawImage(canvas, 0, 0, canvas.width, canvas.height, 0, 0, w, h);
    const base64 = c2.toDataURL('image/jpeg', quality).split(',')[1];
    window.electronAPI?.sendCameraSnapResult?.({ ok: true, base64 });
  } catch (e) {
    window.electronAPI?.sendCameraSnapResult?.({
      ok: false,
      error: e instanceof Error ? e.message : String(e),
    });
  }
}

window.electronAPI?.onCameraSnapStart?.(capture);
