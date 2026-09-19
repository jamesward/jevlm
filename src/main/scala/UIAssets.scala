object UIAssets:
  val tailwindCss: String =
    """
      |body {
      |  background:
      |    radial-gradient(circle at 8% -10%, rgba(34,211,238,.14), transparent 32rem),
      |    radial-gradient(circle at 90% 15%, rgba(99,102,241,.10), transparent 30rem),
      |    #080b12;
      |}
      |.panel { background: linear-gradient(145deg, rgba(255,255,255,.055), rgba(255,255,255,.025)); box-shadow: 0 24px 70px rgba(0,0,0,.24); }
      |.response { overflow-wrap: anywhere; }
      |.word-token { animation: wordIn .32s cubic-bezier(.2,.8,.2,1) both; }
      |@keyframes wordIn { from { opacity: 0; filter: blur(5px); transform: translateY(5px); } to { opacity: 1; filter: blur(0); transform: translateY(0); } }
      |.generating .status-dot { background: #67e8f9; box-shadow: 0 0 18px rgba(103,232,249,.8); animation: pulse 1.25s ease-in-out infinite; }
      |.complete .status-dot { background: #34d399; }
      |.failed .status-dot { background: #fb7185; }
      |@keyframes pulse { 50% { opacity: .35; transform: scale(.8); } }
      |""".stripMargin

  val clientScript: String =
    """
      |(function () {
      |  var form = document.getElementById('prompt-form');
      |  var prompt = document.getElementById('prompt');
      |  var generate = document.getElementById('generate');
      |  var cancel = document.getElementById('cancel');
      |  var responseBox = document.getElementById('response');
      |  var errorBox = document.getElementById('error');
      |  var responseStatus = document.getElementById('response-status');
      |  var elapsed = document.getElementById('elapsed');
      |  var responseCost = document.getElementById('response-cost');
      |  var connection = document.getElementById('connection');
      |  var controller = null;
      |  var timer = null;
      |  var startedAt = 0;
      |  var choiceCount = 0;
      |
      |  function setState(state, label) {
      |    connection.className = 'flex shrink-0 items-center gap-2 text-xs font-semibold text-slate-500 ' + state;
      |    connection.querySelector('.status-label').textContent = label;
      |  }
      |
      |  function showError(message) {
      |    errorBox.textContent = message;
      |    errorBox.classList.remove('hidden');
      |  }
      |
      |  function clearError() {
      |    errorBox.textContent = '';
      |    errorBox.classList.add('hidden');
      |  }
      |
      |  function resetResponse() {
      |    responseBox.innerHTML = '';
      |    choiceCount = 0;
      |    responseCost.textContent = '$0.000000';
      |    document.getElementById('choices').textContent = '0';
      |    document.getElementById('latency').textContent = '—';
      |    document.getElementById('input-tokens').textContent = '0';
      |    document.getElementById('output-tokens').textContent = '0';
      |  }
      |
      |  function appendToken(value, punctuation) {
      |    var token = document.createElement('span');
      |    token.className = 'word-token inline-block';
      |    token.textContent = value;
      |    if (!punctuation && responseBox.childNodes.length) responseBox.appendChild(document.createTextNode(' '));
      |    responseBox.appendChild(token);
      |    choiceCount += 1;
      |    document.getElementById('choices').textContent = String(choiceCount);
      |  }
      |
      |  function startClock() {
      |    startedAt = Date.now();
      |    elapsed.classList.remove('hidden');
      |    responseCost.classList.remove('hidden');
      |    timer = setInterval(function () {
      |      elapsed.textContent = ((Date.now() - startedAt) / 1000).toFixed(1) + 's';
      |    }, 100);
      |  }
      |
      |  function stopClock() {
      |    if (timer) clearInterval(timer);
      |    timer = null;
      |  }
      |
      |  function finish(label, state) {
      |    stopClock();
      |    controller = null;
      |    generate.disabled = false;
      |    cancel.classList.add('hidden');
      |    responseStatus.textContent = label;
      |    setState(state, label);
      |  }
      |
      |  function dispatch(eventName, data) {
      |    if (eventName === 'word') appendToken(data, false);
      |    else if (eventName === 'sentence') appendToken('.', true);
      |    else if (eventName === 'done') {
      |      var summary = JSON.parse(data);
      |      document.getElementById('choices').textContent = String(summary.turns);
      |      document.getElementById('latency').textContent = (summary.jevLatencyMs / 1000).toFixed(1) + 's';
      |      document.getElementById('input-tokens').textContent = String(summary.inputTokens);
      |      document.getElementById('output-tokens').textContent = String(summary.outputTokens);
      |      responseCost.textContent = '$' + Number(summary.estimatedCostUsd || 0).toFixed(6);
      |      finish('Response complete', 'complete');
      |    } else if (eventName === 'error') {
      |      showError(data);
      |      finish('Generation failed', 'failed');
      |    }
      |  }
      |
      |  function parseBlock(block) {
      |    var eventName = 'message';
      |    var data = [];
      |    block.split('\n').forEach(function (line) {
      |      if (line.indexOf('event:') === 0) eventName = line.slice(6).trim();
      |      else if (line.indexOf('data:') === 0) data.push(line.slice(5).replace(/^ /, ''));
      |    });
      |    if (data.length) dispatch(eventName, data.join('\n'));
      |  }
      |
      |  async function consume(stream) {
      |    var reader = stream.getReader();
      |    var decoder = new TextDecoder();
      |    var buffer = '';
      |    while (true) {
      |      var chunk = await reader.read();
      |      buffer += decoder.decode(chunk.value || new Uint8Array(), {stream: !chunk.done}).replace(/\r\n/g, '\n');
      |      var boundary;
      |      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      |        parseBlock(buffer.slice(0, boundary));
      |        buffer = buffer.slice(boundary + 2);
      |      }
      |      if (chunk.done) break;
      |    }
      |    if (buffer.trim()) parseBlock(buffer);
      |  }
      |
      |  form.addEventListener('submit', async function (event) {
      |    event.preventDefault();
      |    clearError();
      |    if (!prompt.value.trim()) { showError('Enter a message for Jev to respond to.'); return; }
      |    if (controller) controller.abort();
      |    controller = new AbortController();
      |    resetResponse();
      |    generate.disabled = true;
      |    cancel.classList.remove('hidden');
      |    responseStatus.textContent = 'Jev is choosing the next word…';
      |    setState('generating', 'Generating');
      |    startClock();
      |    try {
      |      var result = await fetch('/api/generate', {
      |        method: 'POST',
      |        headers: {'content-type': 'text/plain; charset=utf-8', 'accept': 'text/event-stream'},
      |        body: prompt.value,
      |        signal: controller.signal
      |      });
      |      if (!result.ok) throw new Error(await result.text() || 'Could not start generation.');
      |      if (!result.body) throw new Error('Streaming is not supported by this browser.');
      |      await consume(result.body);
      |      if (controller && !errorBox.textContent) finish('Response complete', 'complete');
      |    } catch (error) {
      |      if (error.name === 'AbortError') finish('Generation stopped', '');
      |      else { showError(error.message || 'Generation failed.'); finish('Generation failed', 'failed'); }
      |    }
      |  });
      |
      |  cancel.addEventListener('click', function () {
      |    if (controller) controller.abort();
      |  });
      |
      |  prompt.addEventListener('keydown', function (event) {
      |    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') form.requestSubmit();
      |  });
      |})();
      |""".stripMargin
