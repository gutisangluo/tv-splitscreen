/* ============================================================
   创维TV 分屏控制端 - App Logic
   State Machine, WebSocket, UI Events
   No external dependencies
   ============================================================ */
(function () {
  'use strict';

  // ============================================================
  // STATE
  // ============================================================
  const State = {
    // Connection
    ws: null,
    wsUrl: '',
    connected: false,
    reconnectTimer: null,
    reconnectAttempts: 0,
    maxReconnectAttempts: 10,
    reconnectDelay: 2000,

    // Layout
    currentLayout: 'full',
    isCustom: false,
    customZones: [],
    zoneCount: 1,

    // Zones
    zones: [],
    selectedZoneId: null,

    // TV Status
    tvStatus: null,
    httpPort: 9528,  // 文件上传端口（从pong获取）

    // PING
    pingInterval: null,

    // Screen Cast
    castStream: null,
    castVideo: null,
    castCanvas: null,
    castTimer: null,
    casting: false,
  };

  // ============================================================
  // DOM REFS
  // ============================================================
  const $ = (id) => document.getElementById(id);
  const qs = (s) => document.querySelector(s);
  const qsa = (s) => document.querySelectorAll(s);

  const DOM = {
    statusDot: $('statusDot'),
    statusText: $('statusText'),
    feedbackBar: $('feedbackBar'),
    tvIp: $('tvIp'),
    tvPort: $('tvPort'),
    btnConnect: $('btnConnect'),
    btnDisconnect: $('btnDisconnect'),
    layoutTemplates: $('layoutTemplates'),
    customSlider: $('customSlider'),
    customCount: $('customCount'),
    btnApplyCustom: $('btnApplyCustom'),
    previewGrid: $('previewGrid'),
    selectedZoneLabel: $('selectedZoneLabel'),
    btnClearZone: $('btnClearZone'),
    contentTypeTabs: $('contentTypeTabs'),
    btnSendLayout: $('btnSendLayout'),
    btnGetStatus: $('btnGetStatus'),
    btnSetBg: $('btnSetBg'),
    tvDeviceName: $('tvDeviceName'),
    tvLayout: $('tvLayout'),
    tvZones: $('tvZones'),
    zonePills: $('zonePills'),
    toastContainer: $('toastContainer'),
    bgModal: $('bgModal'),
    bgColorPicker: $('bgColorPicker'),
    bgColorHex: $('bgColorHex'),
    bgApply: $('bgApply'),
  };

  // All content forms
  const contentForms = {
    image: { apply: 'imgApply', fields: ['imgFile', 'imgFit'] },
    video: { apply: 'videoApply', fields: ['videoFile', 'videoLoop', 'videoMute'] },
    web: { apply: 'webApply', fields: ['webUrl', 'webHtml'] },
    text: { apply: 'textApply', fields: ['textContent', 'textSize', 'textAlign', 'textColor', 'textColorHex'] },
    slideshow: { apply: 'slideApply', fields: ['slideFiles', 'slideInterval', 'slideFit'] },
  };

  // ============================================================
  // PRESET LAYOUT DEFINITIONS
  // ============================================================
  const PRESET_LAYOUTS = {
    full:   { name:'全屏', zones:[{id:'z0',x:0,y:0,w:100,h:100}] },
    hsplit: { name:'水平二分', zones:[{id:'z0',x:0,y:0,w:100,h:50},{id:'z1',x:0,y:50,w:100,h:50}] },
    vsplit: { name:'垂直二分', zones:[{id:'z0',x:0,y:0,w:50,h:100},{id:'z1',x:50,y:0,w:50,h:100}] },
    main2:  { name:'主+副(2:1)', zones:[{id:'z0',x:0,y:0,w:66.7,h:100},{id:'z1',x:66.7,y:0,w:33.3,h:100}] },
    quad:   { name:'田字格(4)', zones:[{id:'z0',x:0,y:0,w:50,h:50},{id:'z1',x:50,y:0,w:50,h:50},{id:'z2',x:0,y:50,w:50,h:50},{id:'z3',x:50,y:50,w:50,h:50}] },
    '3p1':  { name:'3+1', zones:[{id:'z0',x:0,y:0,w:50,h:33.3},{id:'z1',x:0,y:33.3,w:50,h:33.3},{id:'z2',x:0,y:66.7,w:50,h:33.3},{id:'z3',x:50,y:0,w:50,h:100}] },
    '2x3':  { name:'2x3(6格)', zones:[
      {id:'z0',x:0,y:0,w:33.3,h:50},{id:'z1',x:33.3,y:0,w:33.3,h:50},{id:'z2',x:66.7,y:0,w:33.3,h:50},
      {id:'z3',x:0,y:50,w:33.3,h:50},{id:'z4',x:33.3,y:50,w:33.3,h:50},{id:'z5',x:66.7,y:50,w:33.3,h:50}
    ]},
    '3x3':  { name:'3x3(9宫)', zones:[
      {id:'z0',x:0,y:0,w:33.3,h:33.3},{id:'z1',x:33.3,y:0,w:33.3,h:33.3},{id:'z2',x:66.7,y:0,w:33.3,h:33.3},
      {id:'z3',x:0,y:33.3,w:33.3,h:33.3},{id:'z4',x:33.3,y:33.3,w:33.3,h:33.3},{id:'z5',x:66.7,y:33.3,w:33.3,h:33.3},
      {id:'z6',x:0,y:66.7,w:33.3,h:33.3},{id:'z7',x:33.3,y:66.7,w:33.3,h:33.3},{id:'z8',x:66.7,y:66.7,w:33.3,h:33.3}
    ]},
    '4p1':  { name:'4+1(4角+中心)', zones:[
      {id:'z0',x:0,y:0,w:40,h:40},{id:'z1',x:60,y:0,w:40,h:40},
      {id:'z2',x:0,y:60,w:40,h:40},{id:'z3',x:60,y:60,w:40,h:40},
      {id:'z4',x:30,y:30,w:40,h:40}
    ]},
  };

  // ============================================================
  // TOAST
  // ============================================================
  function showToast(message, type) {
    type = type || 'info';
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = message;
    DOM.toastContainer.appendChild(el);
    setTimeout(function () {
      el.style.opacity = '0';
      el.style.transition = 'opacity 0.3s';
      setTimeout(function () { el.remove(); }, 300);
    }, 3000);
  }

  // ============================================================
  // FEEDBACK BAR
  // ============================================================
  function showFeedback(msg, type) {
    DOM.feedbackBar.textContent = msg;
    DOM.feedbackBar.className = 'feedback-bar show ' + (type || 'info');
  }
  function hideFeedback() {
    DOM.feedbackBar.className = 'feedback-bar';
  }

  // ============================================================
  // CONNECTION STATUS UI
  // ============================================================
  function setConnectionStatus(status) {
    var dot = DOM.statusDot;
    var text = DOM.statusText;
    dot.className = 'status-dot';
    switch (status) {
      case 'connected':
        dot.classList.add('connected');
        text.textContent = '已连接';
        DOM.btnConnect.style.display = 'none';
        DOM.btnDisconnect.style.display = '';
        showToast('已连接到电视', 'success');
        break;
      case 'connecting':
        dot.classList.add('connecting');
        text.textContent = '连接中...';
        break;
      case 'disconnected':
        dot.classList.add('disconnected');
        text.textContent = '已断开';
        DOM.btnConnect.style.display = '';
        DOM.btnDisconnect.style.display = 'none';
        break;
      default:
        text.textContent = '未连接';
        DOM.btnConnect.style.display = '';
        DOM.btnDisconnect.style.display = 'none';
        break;
    }
  }

  // ============================================================
  // WEBSOCKET
  // ============================================================
  function connectWS() {
    if (State.ws && (State.ws.readyState === WebSocket.OPEN || State.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    var ip = DOM.tvIp.value.trim();
    var port = parseInt(DOM.tvPort.value, 10) || 9000;
    if (!ip) { showFeedback('请输入电视IP地址', 'error'); return; }
    State.wsUrl = 'ws://' + ip + ':' + port;

    setConnectionStatus('connecting');
    showFeedback('正在连接 ' + State.wsUrl + ' ...', 'info');

    try {
      State.ws = new WebSocket(State.wsUrl);
    } catch (e) {
      showFeedback('连接失败: ' + e.message, 'error');
      setConnectionStatus('disconnected');
      return;
    }

    State.ws.onopen = function () {
      State.connected = true;
      State.reconnectAttempts = 0;
      setConnectionStatus('connected');
      hideFeedback();
      startPing();
      // Request status on connect
      sendWS({ type: 'get_status' });
    };

    State.ws.onmessage = function (e) {
      handleWSMessage(e.data);
    };

    State.ws.onclose = function () {
      State.connected = false;
      stopPing();
      setConnectionStatus('disconnected');
      showFeedback('连接已断开', 'error');
      scheduleReconnect();
    };

    State.ws.onerror = function () {
      // onclose will fire after this
    };
  }

  function disconnectWS() {
    stopPing();
    clearReconnect();
    if (State.ws) {
      try { State.ws.close(); } catch (e) { /* ignore */ }
      State.ws = null;
    }
    State.connected = false;
    setConnectionStatus('disconnected');
    showFeedback('已断开连接', 'info');
  }

  function scheduleReconnect() {
    clearReconnect();
    if (State.reconnectAttempts >= State.maxReconnectAttempts) {
      showToast('已达最大重连次数，请手动连接', 'error');
      return;
    }
    State.reconnectAttempts++;
    var delay = State.reconnectDelay * Math.min(State.reconnectAttempts, 5);
    showToast('正在重连 (' + State.reconnectAttempts + '/' + State.maxReconnectAttempts + ')...', 'info');
    State.reconnectTimer = setTimeout(function () {
      if (!State.connected) {
        connectWS();
      }
    }, delay);
  }

  function clearReconnect() {
    if (State.reconnectTimer) {
      clearTimeout(State.reconnectTimer);
      State.reconnectTimer = null;
    }
  }

  function sendWS(data) {
    if (!State.ws || State.ws.readyState !== WebSocket.OPEN) {
      showToast('未连接到电视', 'error');
      return false;
    }
    try {
      var msg = JSON.stringify(data);
      State.ws.send(msg);
      return true;
    } catch (e) {
      showToast('发送失败: ' + e.message, 'error');
      return false;
    }
  }

  // ============================================================
  // PING / KEEP-ALIVE
  // ============================================================
  function startPing() {
    stopPing();
    State.pingInterval = setInterval(function () {
      if (State.connected && State.ws && State.ws.readyState === WebSocket.OPEN) {
        sendWS({ type: 'ping' });
      }
    }, 10000);
  }
  function stopPing() {
    if (State.pingInterval) { clearInterval(State.pingInterval); State.pingInterval = null; }
  }

  // ============================================================
  // WS MESSAGE HANDLER
  // ============================================================
  function handleWSMessage(raw) {
    var msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }

    switch (msg.type) {
      case 'pong':
        // Update status from pong
        if (msg.http_port) State.httpPort = msg.http_port;  // save upload port
        if (msg.device) updateTVStatus({
          device: msg.device,
          layout: msg.layout,
          zones: msg.zones
        });
        break;

      case 'status':
        updateTVStatus(msg);
        break;

      case 'error':
        showToast('电视端错误: ' + (msg.message || '未知错误'), 'error');
        break;

      default:
        // Unknown type, ignore
        break;
    }
  }

  function updateTVStatus(data) {
    State.tvStatus = data;
    DOM.tvDeviceName.textContent = data.device || '创维TV';
    DOM.tvLayout.textContent = data.layout || '—';
    DOM.tvZones.textContent = (data.zones !== undefined) ? data.zones : '—';

    // Zone pills
    var pills = DOM.zonePills;
    pills.innerHTML = '';
    if (data.zoneList && data.zoneList.length > 0) {
      data.zoneList.forEach(function (z) {
        var pill = document.createElement('span');
        pill.className = 'zone-pill';
        pill.textContent = '#' + z.id + ' ' + (z.type || '空');
        pills.appendChild(pill);
      });
    } else {
      var empty = document.createElement('span');
      empty.style.cssText = 'color:var(--text-muted);font-size:0.78rem;';
      empty.textContent = '暂无数据';
      pills.appendChild(empty);
    }

    // If layout changed on TV, sync UI
    if (data.layout && data.layout !== State.currentLayout) {
      var btn = qs('[data-layout="' + data.layout + '"]');
      if (btn) {
        qsa('.layout-btn').forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        State.currentLayout = data.layout;
        State.isCustom = false;
      }
    }
  }

  // ============================================================
  // LAYOUT PRESETS
  // ============================================================
  function selectLayout(layoutKey) {
    qsa('.layout-btn').forEach(function (b) {
      b.classList.remove('active');
      if (b.getAttribute('data-layout') === layoutKey) {
        b.classList.add('active');
      }
    });
    State.currentLayout = layoutKey;
    State.isCustom = false;

    var preset = PRESET_LAYOUTS[layoutKey];
    if (preset) {
      State.zones = preset.zones.map(function (z, i) {
        return { id: i, name: 'z' + i, x: z.x, y: z.y, w: z.w, h: z.h, type: null };
      });
      State.zoneCount = State.zones.length;
    }
    renderPreview();
    // Reset zone selection
    selectZone(null);
  }

  // ============================================================
  // CUSTOM LAYOUT
  // ============================================================
  function generateCustomLayout(count) {
    count = Math.max(1, Math.min(24, count));
    var cols = Math.ceil(Math.sqrt(count));
    var rows = Math.ceil(count / cols);
    var cellW = 100 / cols;
    var cellH = 100 / rows;
    var zones = [];
    var idx = 0;
    for (var r = 0; r < rows; r++) {
      for (var c = 0; c < cols; c++) {
        if (idx >= count) break;
        zones.push({
          id: idx,
          name: 'z' + idx,
          x: c * cellW,
          y: r * cellH,
          w: cellW,
          h: cellH,
          type: null
        });
        idx++;
      }
    }
    return zones;
  }

  function applyCustomLayout(count) {
    count = count || parseInt(DOM.customSlider.value, 10);
    State.isCustom = true;
    State.currentLayout = 'custom';
    State.zoneCount = count;
    State.zones = generateCustomLayout(count);
    // Deselect preset buttons
    qsa('.layout-btn').forEach(function (b) { b.classList.remove('active'); });
    renderPreview();
    selectZone(null);
  }

  // ============================================================
  // PREVIEW RENDER
  // ============================================================
  function renderPreview() {
    var grid = DOM.previewGrid;
    grid.innerHTML = '';
    if (!State.zones || State.zones.length === 0) return;

    var cols = 1;
    var rows = 1;
    // Determine grid layout for preview
    var zones = State.zones;

    // Try to figure out a decent grid representation
    var uniqueX = {};
    var uniqueY = {};
    zones.forEach(function (z) {
      var cx = Math.round(z.x * 10) / 10;
      var cy = Math.round(z.y * 10) / 10;
      uniqueX[cx] = true;
      uniqueY[cy] = true;
    });
    cols = Object.keys(uniqueX).length;
    rows = Object.keys(uniqueY).length;
    if (cols < 1) cols = 1;
    if (rows < 1) rows = 1;

    grid.style.gridTemplateColumns = 'repeat(' + cols + ', 1fr)';
    grid.style.gridTemplateRows = 'repeat(' + rows + ', 1fr)';

    // Build position map
    var xPositions = Object.keys(uniqueX).map(Number).sort(function (a, b) { return a - b; });
    var yPositions = Object.keys(uniqueY).map(Number).sort(function (a, b) { return a - b; });

    zones.forEach(function (z) {
      var el = document.createElement('div');
      el.className = 'preview-zone';
      el.dataset.zoneId = z.id;

      // Find grid position
      var col = xPositions.indexOf(Math.round(z.x * 10) / 10) + 1;
      var row = yPositions.indexOf(Math.round(z.y * 10) / 10) + 1;
      var spanW = 1;
      var spanH = 1;

      // Calculate spans
      if (z.w && cols > 1) {
        var endX = Math.round((z.x + z.w) * 10) / 10;
        var endCol = 0;
        for (var i = 0; i < xPositions.length; i++) {
          if (xPositions[i] >= endX - 0.01) { endCol = i + 1; break; }
        }
        if (!endCol) endCol = cols + 1;
        spanW = Math.max(1, endCol - col);
      }
      if (z.h && rows > 1) {
        var endY = Math.round((z.y + z.h) * 10) / 10;
        var endRow = 0;
        for (var j = 0; j < yPositions.length; j++) {
          if (yPositions[j] >= endY - 0.01) { endRow = j + 1; break; }
        }
        if (!endRow) endRow = rows + 1;
        spanH = Math.max(1, endRow - row);
      }

      if (spanW > 1) el.style.gridColumn = col + ' / span ' + spanW;
      if (spanH > 1) el.style.gridRow = row + ' / span ' + spanH;

      // Zone label
      var label = document.createElement('span');
      label.className = 'zone-label';
      label.textContent = '#' + z.id;
      el.appendChild(label);

      // Thumbnail for image/video/slideshow zones
      if (z.thumbnailLocal) {
        // Use local data URL (no TV connection needed)
        el.style.backgroundImage = 'url(' + z.thumbnailLocal + ')';
        el.style.backgroundSize = 'cover';
        el.style.backgroundPosition = 'center';
      } else if (z.type === 'video') {
        // Video overlay icon
        var vi = document.createElement('div');
        vi.className = 'zone-video-indicator';
        vi.textContent = '▶';
        el.appendChild(vi);
      }

      // Type icon
      var icon = document.createElement('span');
      icon.className = 'zone-type-icon';
      if (z.type) {
        var iconsMap = { image:'🖼', video:'🎬', web:'🌐', text:'📝', slideshow:'📽', screencast:'📺', groupchat:'💬' };
        icon.textContent = iconsMap[z.type] || '';
      }
      // Don't overlay icon on top of thumbnail
      if (!z.thumbnailLocal) {
        el.appendChild(icon);
      } else {
        icon.style.display = 'none';
      }

      el.addEventListener('click', function () {
        selectZone(parseInt(this.dataset.zoneId, 10));
      });

      if (State.selectedZoneId !== null && z.id === State.selectedZoneId) {
        el.classList.add('selected');
      }

      grid.appendChild(el);
    });
  }

  // ============================================================
  // ZONE SELECTION
  // ============================================================
  function selectZone(zoneId) {
    State.selectedZoneId = zoneId;
    // Update preview
    qsa('.preview-zone').forEach(function (el) {
      el.classList.toggle('selected', parseInt(el.dataset.zoneId, 10) === zoneId);
    });

    // Update label
    if (zoneId === null) {
      DOM.selectedZoneLabel.textContent = '未选中分区';
      // Disable content form
      DOM.contentTypeTabs.style.opacity = '0.4';
      DOM.contentTypeTabs.style.pointerEvents = 'none';
      qsa('.content-form').forEach(function (f) { f.classList.remove('active'); });
    } else {
      DOM.selectedZoneLabel.textContent = '分区 #' + zoneId;
      DOM.contentTypeTabs.style.opacity = '1';
      DOM.contentTypeTabs.style.pointerEvents = '';

      // Load existing type if any
      var zone = State.zones[zoneId];
      if (zone && zone.type) {
        activateContentTab(zone.type);
      } else {
        // Default to first tab
        activateContentTab('image');
      }
    }
  }

  function getSelectedZone() {
    if (State.selectedZoneId === null) return null;
    return State.zones[State.selectedZoneId] || null;
  }

  // ============================================================
  // CONTENT TYPE TABS
  // ============================================================
  function activateContentTab(type) {
    qsa('.type-tab').forEach(function (t) {
      t.classList.toggle('active', t.getAttribute('data-type') === type);
    });
    qsa('.content-form').forEach(function (f) {
      f.classList.toggle('active', f.id === 'form-' + type);
    });
  }

  // ============================================================
  // LOCAL FILE PREVIEW
  // ============================================================
  function setupFilePreview(inputId, zoneType) {
    var input = $(inputId);
    if (!input) return;
    input.addEventListener('change', function () {
      var zone = getSelectedZone();
      if (!zone || !this.files || this.files.length === 0) return;
      var file = this.files[0];
      // Store type
      zone.type = zoneType;
      // Read file as data URL for preview
      var reader = new FileReader();
      reader.onload = function (e) {
        zone.thumbnailLocal = e.target.result;
        renderPreview();
      };
      reader.readAsDataURL(file);
    });
  }

  function setupSlidePreview() {
    var input = $('slideFiles');
    if (!input) return;
    input.addEventListener('change', function () {
      var zone = getSelectedZone();
      if (!zone || !this.files || this.files.length === 0) return;
      zone.type = 'slideshow';
      // Use first file as preview
      var reader = new FileReader();
      reader.onload = function (e) {
        zone.thumbnailLocal = e.target.result;
        renderPreview();
      };
      reader.readAsDataURL(this.files[0]);
    });
  }

  // ============================================================
  // UPLOAD PROGRESS BAR
  // ============================================================
  function showProgress(label) {
    var bar = $('uploadProgress');
    var labelEl = $('uploadProgressLabel');
    if (bar) bar.style.display = 'block';
    if (labelEl) labelEl.textContent = label || '上传中...';
    updateProgress(0);
  }
  function updateProgress(pct) {
    var bar = $('uploadProgressBar');
    if (bar) bar.style.width = Math.min(pct, 100) + '%';
  }
  function hideProgress() {
    var bar = $('uploadProgress');
    if (bar) bar.style.display = 'none';
    updateProgress(0);
  }

  // ============================================================
  // FILE UPLOAD TO TV
  // ============================================================
  function uploadFile(file, progressCb) {
    return new Promise(function (resolve, reject) {
      var ip = DOM.tvIp.value.trim();
      if (!ip) { reject('未设置电视IP'); return; }
      var port = State.httpPort || 9528;
      var url = 'http://' + ip + ':' + port + '/upload';

      var formData = new FormData();
      formData.append(file.name, file);

      var xhr = new XMLHttpRequest();
      xhr.open('POST', url, true);

      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable && progressCb) {
          progressCb(Math.round(e.loaded / e.total * 100));
        }
      };

      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            var resp = JSON.parse(xhr.responseText);
            resolve(resp);
          } catch (e) {
            resolve({ url: '/media/upload', name: file.name });
          }
        } else {
          reject('上传失败: HTTP ' + xhr.status);
        }
      };

      xhr.onerror = function () { reject('网络错误，无法连接电视'); };
      xhr.send(formData);
    });
  }

  // ============================================================
  // SEND CONTENT TO TV
  // ============================================================
  function sendContentToTV(type) {
    var zone = getSelectedZone();
    if (!zone) { showToast('请先选择一个分区', 'error'); return; }

    var params = {};
    var form = contentForms[type];
    if (!form) return;

    // --- File upload types (image, video) ---
    if (type === 'image') {
      var fileInput = $('imgFile');
      if (fileInput && fileInput.files && fileInput.files.length > 0) {
        var file = fileInput.files[0];
        showProgress('上传图片: ' + file.name);
        uploadFile(file, function (pct) {
          updateProgress(pct);
        }).then(function (resp) {
          hideProgress();
          params.url = resp.url;
          params.fit = ($('imgFit') || {}).value || 'cover';
          sendContentParams(type, zone, params);
        }).catch(function (err) {
          hideProgress();
          showToast(err, 'error');
        });
        return;
      }
      showToast('请选择要上传的图片', 'error');
      return;
    }

    if (type === 'video') {
      var fileInput = $('videoFile');
      if (fileInput && fileInput.files && fileInput.files.length > 0) {
        var file = fileInput.files[0];
        showProgress('上传视频: ' + file.name);
        uploadFile(file, function (pct) {
          updateProgress(pct);
        }).then(function (resp) {
          hideProgress();
          params.url = resp.url;
          params.loop = $('videoLoop') ? $('videoLoop').checked : false;
          params.mute = $('videoMute') ? $('videoMute').checked : false;
          sendContentParams(type, zone, params);
        }).catch(function (err) {
          hideProgress();
          showToast(err, 'error');
        });
        return;
      }
      showToast('请选择要上传的视频', 'error');
      return;
    }

    // --- Slideshow (multiple files) ---
    if (type === 'slideshow') {
      var fileInput = $('slideFiles');
      var files = (fileInput && fileInput.files) ? fileInput.files : [];
      if (files.length > 0) {
        var total = files.length;
        var urls = [];
        showProgress('上传幻灯片 (1/' + total + ')');

        function uploadNext(idx) {
          if (idx >= total) {
            hideProgress();
            params.urls = urls;
            params.interval = (parseInt(($('slideInterval') || {}).value, 10) || 5) * 1000;
            params.fit = ($('slideFit') || {}).value || 'cover';
            sendContentParams(type, zone, params);
            return;
          }
          $('uploadProgressLabel').textContent = '上传幻灯片 (' + (idx + 1) + '/' + total + ')';
          uploadFile(files[idx], function (pct) {
            var overall = Math.round((idx / total) * 100 + (pct / total));
            updateProgress(overall);
          }).then(function (resp) {
            urls.push(resp.url);
            // Save first image as thumbnail
            if (idx === 0 && resp.url) zone.thumbnailUrl = resp.url;
            updateProgress(Math.round(((idx + 1) / total) * 100));
            uploadNext(idx + 1);
          }).catch(function (err) {
            showToast('第' + (idx + 1) + '张失败: ' + err, 'error');
            uploadNext(idx + 1);
          });
        }
        uploadNext(0);
        return;
      }
      showToast('请选择要上传的图片', 'error');
      return;
    }

    // --- Non-file types ---
    form.fields.forEach(function (fid) {
      var el = $(fid);
      if (el) {
        var val = el.value.trim();
        if (fid === 'textColor' || fid === 'textColorHex') {
          // Handle color syncing
          if (fid === 'textColorHex') params.textColor = val;
          else params.textColor = el.value;
        } else if (fid === 'clockColor' || fid === 'clockColorHex') {
          if (fid === 'clockColorHex') params.color = val;
          else params.color = el.value;
        } else if (fid === 'textSize' || fid === 'clockSize') {
          params.size = parseInt(val, 10) || 48;
        } else if (fid === 'slideInterval') {
          params.interval = parseInt(val, 10) || 5;
        } else if (fid === 'slideUrls') {
          params.urls = val.split(',').map(function (u) { return u.trim(); }).filter(Boolean);
        } else {
          params[fid] = val;
        }
      }
    });

    // Handle textColor specially
    if (type === 'text') {
      var tcHex = $('textColorHex').value.trim();
      params.color = tcHex || '#ffffff';
      params.size = parseInt($('textSize').value, 10) || 48;
      params.align = $('textAlign').value;
      params.text = params.textContent || '';
      delete params.textContent;
      delete params.textColor;
      delete params.textColorHex;
      delete params.textSize;
      delete params.textAlign;
    }

    if (type === 'web') {
      var url = $('webUrl').value.trim();
      var html = $('webHtml').value.trim();
      if (url) params.url = url;
      if (html) params.html = html;
      delete params.webUrl;
      delete params.webHtml;
    }
    sendContentParams(type, zone, params);
  }

  /** 通过 WebSocket 发送内容到电视 */
  function sendContentParams(type, zone, params) {
    // 先确保电视端布局正确（用户可能只选了但没点"发送布局"）
    sendWS({
      type: 'set_layout',
      layout: State.currentLayout
    });

    var msg = {
      type: 'set_content',
      zone_id: zone.id,
      content_type: type,
      params: params
    };

    // Update local zone state
    zone.type = type;
    // Save thumbnail URL for preview
    if (params.url) {
      zone.thumbnailUrl = params.url;
    }

    if (sendWS(msg)) {
      showToast('已发送到分区 #' + zone.id, 'success');
      renderPreview();
      setTimeout(function () { sendWS({ type: 'get_status' }); }, 500);
    }
  }

  // ============================================================
  // CLEAR ZONE
  // ============================================================
  function clearSelectedZone() {
    var zone = getSelectedZone();
    if (!zone) { showToast('请先选择一个分区', 'error'); return; }
    if (sendWS({ type: 'clear_zone', zone_id: zone.id })) {
      zone.type = null;
      zone.thumbnailLocal = null;
      zone.thumbnailUrl = null;
      showToast('已清空分区 #' + zone.id, 'info');
      renderPreview();
    }
  }

  // ============================================================
  // SEND LAYOUT
  // ============================================================
  function sendLayoutToTV() {
    var msg;
    if (State.isCustom) {
      msg = {
        type: 'set_layout',
        layout: 'custom',
        zones: State.zones.map(function (z) {
          return {
            id: 'z' + z.id,
            x: Math.round(z.x * 10) / 10,
            y: Math.round(z.y * 10) / 10,
            w: Math.round(z.w * 10) / 10,
            h: Math.round(z.h * 10) / 10
          };
        })
      };
    } else {
      msg = { type: 'set_layout', layout: State.currentLayout };
    }
    if (sendWS(msg)) {
      showToast('已发送布局', 'success');
      setTimeout(function () { sendWS({ type: 'get_status' }); }, 500);
    }
  }

  // ============================================================
  // SET BACKGROUND
  // ============================================================
  function openBgModal() {
    DOM.bgModal.style.display = 'flex';
  }
  function closeBgModal() {
    DOM.bgModal.style.display = 'none';
  }
  // Expose closeBgModal globally for onclick
  window.closeBgModal = closeBgModal;

  function applyBgColor() {
    var color = DOM.bgColorHex.value.trim() || '#000000';
    if (sendWS({ type: 'set_bg', color: color })) {
      showToast('已设置背景色 ' + color, 'success');
      closeBgModal();
    }
  }

  // Sync color picker <-> hex input
  function syncColorInput(pickerId, hexId) {
    var picker = $(pickerId);
    var hex = $(hexId);
    if (!picker || !hex) return;
    picker.addEventListener('input', function () { hex.value = this.value; });
    hex.addEventListener('input', function () {
      if (/^#[0-9a-fA-F]{6}$/.test(this.value)) {
        picker.value = this.value;
      }
    });
  }

  // ============================================================
  // CONNECTION FORM: SAVE IP/PORT
  // ============================================================
  function loadSavedConnection() {
    try {
      var ip = localStorage.getItem('tv_ip');
      var port = localStorage.getItem('tv_port');
      if (ip) DOM.tvIp.value = ip;
      if (port) DOM.tvPort.value = port;
    } catch (e) { /* ignore */ }
  }
  function saveConnection() {
    try {
      localStorage.setItem('tv_ip', DOM.tvIp.value.trim());
      localStorage.setItem('tv_port', DOM.tvPort.value);
    } catch (e) { /* ignore */ }
  }

  // ============================================================
  // SCREEN CAST
  // ============================================================
  function startScreenCast(zone) {
    if (State.casting) return;
    if (!State.connected) { showToast('请先连接到电视', 'error'); return; }

    try {
      navigator.mediaDevices.getDisplayMedia({ video: true })
        .then(function (stream) {
          State.castStream = stream;
          State.casting = true;

          // Create hidden video element to capture frames
          var video = document.createElement('video');
          State.castVideo = video;
          video.srcObject = stream;
          video.play();

          // Create canvas for frame encoding
          var canvas = document.createElement('canvas');
          State.castCanvas = canvas;
          var ctx = canvas.getContext('2d');

          // Update UI
          $('castStatus').textContent = '📺 正在投屏…';
          $('castStatus').style.color = '#00FF88';
          $('castStart').disabled = true;
          $('castStop').disabled = false;

          // Send screencast content type to TV zone
          var fit = ($('castFit') || {}).value || 'contain';
          sendWS({
            type: 'set_content',
            content_type: 'screencast',
            zone_id: zone.id,
            params: { fit: fit }
          });

          // Periodic frame capture and upload (3fps)
          var tvIp = DOM.tvIp.value.trim();
          var tvPort = DOM.tvPort.value.trim();
          State.castTimer = setInterval(function () {
            if (!State.casting || !video.videoWidth) return;
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            ctx.drawImage(video, 0, 0);

            canvas.toBlob(function (blob) {
              if (!blob || !State.casting) return;
              var fd = new FormData();
              fd.append('screencast.jpg', blob, 'screencast.jpg');
              // Upload to TV HTTP server (fire and forget)
              fetch('http://' + tvIp + ':9528/upload', {
                method: 'POST',
                body: fd
              }).catch(function () { /* ignore upload errors */ });
            }, 'image/jpeg', 65);
          }, 350);

          // Listen for stream end (user clicks "Stop sharing" in browser)
          stream.getVideoTracks()[0].addEventListener('ended', function () {
            stopScreenCast();
          });
        })
        .catch(function (err) {
          showToast('投屏取消或失败: ' + err.message, 'error');
        });
    } catch (e) {
      showToast('浏览器不支持投屏，请使用桌面端控制', 'error');
    }
  }

  function stopScreenCast() {
    State.casting = false;
    if (State.castTimer) { clearInterval(State.castTimer); State.castTimer = null; }
    if (State.castStream) {
      State.castStream.getTracks().forEach(function (t) { t.stop(); });
      State.castStream = null;
    }
    State.castVideo = null;
    State.castCanvas = null;

    $('castStatus').textContent = '📺 投屏已停止';
    $('castStatus').style.color = '';
    $('castStart').disabled = false;
    $('castStop').disabled = true;

    showToast('投屏已停止', 'info');
  }

  // ============================================================
  // INIT
  // ============================================================
  function init() {
    loadSavedConnection();

    // ---- Connection events ----
    DOM.btnConnect.addEventListener('click', function () {
      saveConnection();
      connectWS();
    });
    DOM.btnDisconnect.addEventListener('click', disconnectWS);

    // Enter key in IP/port fields
    DOM.tvIp.addEventListener('keydown', function (e) { if (e.key === 'Enter') { saveConnection(); connectWS(); } });
    DOM.tvPort.addEventListener('keydown', function (e) { if (e.key === 'Enter') { saveConnection(); connectWS(); } });

    // ---- Layout preset selection ----
    DOM.layoutTemplates.addEventListener('click', function (e) {
      var btn = e.target.closest('.layout-btn');
      if (!btn) return;
      var layout = btn.getAttribute('data-layout');
      if (layout) selectLayout(layout);
    });

    // ---- Custom layout ----
    DOM.customSlider.addEventListener('input', function () {
      var val = parseInt(this.value, 10);
      DOM.customCount.textContent = val;
    });
    DOM.btnApplyCustom.addEventListener('click', function () {
      var count = parseInt(DOM.customSlider.value, 10);
      applyCustomLayout(count);
    });

    // ---- Content type tabs ----
    DOM.contentTypeTabs.addEventListener('click', function (e) {
      var tab = e.target.closest('.type-tab');
      if (!tab) return;
      var type = tab.getAttribute('data-type');
      if (type) activateContentTab(type);
    });

    // ---- Content apply buttons ----
    Object.keys(contentForms).forEach(function (type) {
      var btnId = contentForms[type].apply;
      var btn = $(btnId);
      if (btn) {
        btn.addEventListener('click', function () { sendContentToTV(type); });
      }
    });

    // ---- Clear zone ----
    DOM.btnClearZone.addEventListener('click', clearSelectedZone);

    // ---- Send layout ----
    DOM.btnSendLayout.addEventListener('click', sendLayoutToTV);

    // ---- Get status ----
    DOM.btnGetStatus.addEventListener('click', function () {
      if (sendWS({ type: 'get_status' })) {
        showToast('已请求状态', 'info');
      }
    });

    // ---- Background color ----
    DOM.btnSetBg.addEventListener('click', openBgModal);
    DOM.bgApply.addEventListener('click', applyBgColor);

    // ---- Global tool: Scroll text ----
    $('scrollApply').addEventListener('click', function () {
      var text = $('scrollContent').value.trim();
      if (!text) { showToast('请输入滚动文字', 'error'); return; }
      sendWS({
        type: 'set_content',
        content_type: 'scroll',
        zone_id: 0,
        params: {
          text: text,
          direction: $('scrollDirection').value,
          position: $('scrollPosition').value,
          fontSize: parseInt($('scrollSize').value, 10) || 16
        }
      });
      showToast('滚动文字已发送', 'success');
    });

    // ---- Global tool: Clock ----
    $('clockApply').addEventListener('click', function () {
      var hex = $('clockColorHex').value.trim();
      sendWS({
        type: 'set_content',
        content_type: 'clock',
        zone_id: 0,
        params: {
          format: $('clockFormat').value,
          size: parseInt($('clockSize').value, 10) || 64,
          color: hex || '#ffffff',
          position: $('clockPosition').value
        }
      });
      showToast('时钟已发送', 'success');
    });

    $('clockHide').addEventListener('click', function () {
      sendWS({
        type: 'set_content',
        content_type: 'clock',
        zone_id: 0,
        params: { hide: true }
      });
      showToast('时钟已隐藏', 'success');
    });

    // ---- Screen Cast ----
    $('castStart').addEventListener('click', function () {
      var zone = getSelectedZone();
      if (!zone) { showToast('请先选择一个分区', 'error'); return; }
      startScreenCast(zone);
    });
    $('castStop').addEventListener('click', stopScreenCast);

    // ---- Group Chat ----
    $('groupChatSend').addEventListener('click', function () {
      var text = $('groupChatInput').value.trim();
      if (!text) { showToast('请输入消息内容', 'error'); return; }
      if (!State.connected) { showToast('请先连接到电视', 'error'); return; }

      var sender = $('groupChatSender').value.trim() || '群友';
      var tvIp = DOM.tvIp.value.trim();
      var payload = JSON.stringify({ sender: sender, text: text });

      fetch('http://' + tvIp + ':9528/groupchat/msg', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: payload
      }).then(function (r) { return r.json(); })
        .then(function (d) {
          if (d.ok) {
            showToast('消息已发送到电视', 'success');
            $('groupChatInput').value = '';
          }
        })
        .catch(function (err) {
          showToast('发送失败: ' + err.message, 'error');
        });
    });

    $('groupChatStart').addEventListener('click', function () {
      var zone = getSelectedZone();
      if (!zone) { showToast('请先选择一个分区', 'error'); return; }
      if (!State.connected) { showToast('请先连接到电视', 'error'); return; }

      // 发送 groupchat 内容类型，让电视显示消息轮播
      sendWS({
        type: 'set_content',
        content_type: 'groupchat',
        zone_id: zone.id,
        params: {}
      });

      $('groupChatStatus').textContent = '💬 消息轮播已启动';
      $('groupChatStatus').style.color = '#00FF88';
      $('groupChatStart').disabled = true;
      $('groupChatStop').disabled = false;

      showToast('消息轮播已启动', 'success');
    });
    $('groupChatStop').addEventListener('click', function () {
      var zone = getSelectedZone();
      if (zone) {
        sendWS({ type: 'clear_zone', zone_id: zone.id });
      }

      $('groupChatStatus').textContent = '💬 消息轮播已停止';
      $('groupChatStatus').style.color = '';
      $('groupChatStart').disabled = false;
      $('groupChatStop').disabled = true;

      showToast('消息轮播已停止', 'info');
    });
    $('groupChatClear').addEventListener('click', function () {
      var tvIp = DOM.tvIp.value.trim();
      fetch('http://' + tvIp + ':9528/groupchat/clear')
        .then(function () { showToast('消息已清空', 'info'); })
        .catch(function () { showToast('清空失败', 'error'); });
    });

    // Color sync
    syncColorInput('textColor', 'textColorHex');
    syncColorInput('clockColor', 'clockColorHex');
    syncColorInput('bgColorPicker', 'bgColorHex');

    // ---- File preview (select file → show thumbnail) ----
    setupFilePreview('imgFile', 'image');
    setupFilePreview('videoFile', 'video');
    setupSlidePreview();

    // ---- Initial render ----
    selectLayout('full');
  }

  // ============================================================
  // BOOT
  // ============================================================
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
