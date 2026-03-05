/* eslint-env browser */
(function () {
  const gatewayUrlEl = document.getElementById('gatewayUrl');
  const displayNameEl = document.getElementById('displayName');
  const statusDotEl = document.getElementById('statusDot');
  const statusTextEl = document.getElementById('statusText');
  const btnConnect = document.getElementById('btnConnect');
  const btnDisconnect = document.getElementById('btnDisconnect');
  const toggleStartup = document.getElementById('toggleStartup');

  function setStatus(status) {
    statusDotEl.className = 'statusDot';
    if (status === 'CONNECTED') {
      statusDotEl.classList.add('connected');
      statusTextEl.textContent = '已连接';
      btnConnect.disabled = true;
      btnDisconnect.disabled = false;
    } else if (status === 'CONNECTING' || status === 'PENDING_PAIRING') {
      statusDotEl.classList.add(status === 'PENDING_PAIRING' ? 'pending' : 'connecting');
      statusTextEl.textContent = status === 'PENDING_PAIRING' ? '等待配对审批…' : '连接中…';
      btnConnect.disabled = true;
      btnDisconnect.disabled = false;
    } else if (status === 'CONNECT_FAILED') {
      statusDotEl.classList.add('failed');
      statusTextEl.textContent = '连接失败，正在重试…';
      btnConnect.disabled = false;
      btnDisconnect.disabled = false;
    } else {
      statusTextEl.textContent = '未连接';
      btnConnect.disabled = false;
      btnDisconnect.disabled = true;
    }
  }

  function init() {
    window.mainAPI.onStatusUpdate(setStatus);
    window.mainAPI.getConfig().then(function (cfg) {
      if (cfg && cfg.gatewayUrl) gatewayUrlEl.value = cfg.gatewayUrl;
      if (cfg && cfg.displayName) displayNameEl.value = cfg.displayName;
    }).catch(function () {});
    window.mainAPI.getLaunchAtStartup().then(function (enabled) {
      toggleStartup.classList.toggle('on', !!enabled);
    }).catch(function () {});
    window.mainAPI.getCurrentStatus().then(function (s) {
      if (s) setStatus(s);
    }).catch(function () {});
    window.mainAPI.notifyReady();
  }

  btnConnect.addEventListener('click', function () {
    const url = gatewayUrlEl.value.trim();
    const name = displayNameEl.value.trim();
    if (!url) {
      alert('请输入 Gateway 地址');
      return;
    }
    setStatus('CONNECTING');
    window.mainAPI.doConnect(url, name || 'Desktop Node').catch(function (err) {
      setStatus('DISCONNECTED');
      alert('连接失败: ' + (err && err.message ? err.message : err));
    });
  });

  btnDisconnect.addEventListener('click', function () {
    setStatus('DISCONNECTED');
    window.mainAPI.doDisconnect().catch(function () {});
  });

  toggleStartup.addEventListener('click', function () {
    const next = !toggleStartup.classList.contains('on');
    toggleStartup.classList.toggle('on', next);
    window.mainAPI.setLaunchAtStartup(next);
  });

  init();
})();
