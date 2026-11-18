const http = require('http');
const urlParser = require('url');
const fs = require('fs');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

// CHROME_PATH (variable d'environnement) en premier : Chromium du conteneur Docker sur le VPS
const POSSIBLE_BROWSERS = [
  process.env.CHROME_PATH,
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'
];

function findBrowserPath() {
  for (const p of POSSIBLE_BROWSERS) {
    if (p && fs.existsSync(p)) return p;
  }
  return 'msedge';
}

const PORT = Number(process.env.PORT || 8099);
// 127.0.0.1 sur le poste ; 0.0.0.0 dans le conteneur, pour que le back (autre conteneur) puisse l'appeler
const HOST = process.env.HOST || '127.0.0.1';
let browser = null;

async function getBrowser() {
  if (!browser || (typeof browser.isConnected === 'function' ? !browser.isConnected() : !browser.connected)) {
    browser = await puppeteer.launch({
      executablePath: findBrowserPath(),
      headless: 'new',
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-blink-features=AutomationControlled',
        '--window-size=1920,1080'
      ]
    });
  }
  return browser;
}

const challengeKeywords = [
  'just a moment',
  'un instant',
  'security check',
  'voight-kampff',
  'a verificar se a ligação',
  'checking your browser',
  'attention required',
  'challenge-platform'
];

// Page de défi encore affichée : titre connu (Cloudflare…) ou adresse /.enodia/challenge (sites fédéraux allemands)
async function onChallenge(page) {
  if (page.url().includes('/.enodia/')) return true;
  const title = (await page.title().catch(() => '')).toLowerCase();
  return challengeKeywords.some(kw => title.includes(kw));
}

// Attend jusqu'à 30 s que le navigateur résolve le défi ; true si la page n'est plus une page de défi
async function solveChallenge(page) {
  for (let i = 0; i < 30; i++) {
    if (!(await onChallenge(page))) return true;
    const turnstileFrame = page.frames().find(f => f.url().includes('challenge-platform') || f.url().includes('turnstile'));
    if (turnstileFrame) {
      try {
        const btn = await turnstileFrame.$('input[type="checkbox"], #challenge-stage, .ctp-checkbox-label, label');
        if (btn) await btn.click();
      } catch (e) {}
    }
    await new Promise(r => setTimeout(r, 1000));
  }
  return !(await onChallenge(page));
}

// Télécharge le fichier depuis la page (cookies du défi compris) ; octets en base64
function fetchInPage(page, url) {
  return page.evaluate(async (u) => {
    const resp = await fetch(u, { credentials: 'include', redirect: 'follow' });
    const buf = new Uint8Array(await resp.arrayBuffer());
    let s = '';
    for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
    return { status: resp.status, type: resp.headers.get('content-type') || '', finalUrl: resp.url, b64: btoa(s) };
  }, url);
}

function fail(res, code, error, url) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error, url }));
}

const server = http.createServer(async (req, res) => {
  const parsed = urlParser.parse(req.url, true);
  if (parsed.pathname === '/download' && parsed.query.url) {
    // Fichier binaire (PDF, zip, XML…) derrière un défi anti-robot : le navigateur ouvre le site, passe le défi,
    // puis télécharge le fichier avec ses cookies. Réponse : les octets bruts (en-tête X-Upstream-Status).
    const targetUrl = parsed.query.url;
    let page = null;
    try {
      const origin = new URL(targetUrl).origin;
      const b = await getBrowser();
      page = await b.newPage();
      await page.setViewport({ width: 1920, height: 1080 });
      await page.setExtraHTTPHeaders({ 'Accept-Language': 'de-DE,de;q=0.9,fr-FR;q=0.8,en;q=0.7' });
      await page.goto(origin + '/', { waitUntil: 'domcontentloaded', timeout: 35000 });
      if (!(await solveChallenge(page))) {
        return fail(res, 502, 'défi anti-robot non résolu sur ' + origin + ' : ' + page.url(), targetUrl);
      }
      let r = await fetchInPage(page, targetUrl);
      if (r.status !== 200 || r.finalUrl.includes('/.enodia/')) {
        // défi propre au chemin du fichier : l'ouvrir dans le navigateur (un zip interrompt la navigation, sans gravité)
        await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 35000 }).catch(() => {});
        await solveChallenge(page);
        if (!page.url().startsWith(origin)) await page.goto(origin + '/', { waitUntil: 'domcontentloaded', timeout: 35000 }).catch(() => {});
        r = await fetchInPage(page, targetUrl);
      }
      if (r.status !== 200 || r.finalUrl.includes('/.enodia/')) {
        return fail(res, 502, 'HTTP ' + r.status + ' après le défi (' + r.finalUrl + ')', targetUrl);
      }
      const body = Buffer.from(r.b64, 'base64');
      res.writeHead(200, {
        'Content-Type': r.type || 'application/octet-stream',
        'Content-Length': body.length,
        'X-Upstream-Status': String(r.status),
        'X-Final-Url': encodeURI(r.finalUrl)
      });
      res.end(body);
    } catch (e) {
      fail(res, 500, e.message, targetUrl);
    } finally {
      if (page) await page.close().catch(() => {});
    }
  } else if (parsed.pathname === '/fetch' && parsed.query.url) {
    const targetUrl = parsed.query.url;
    let page = null;
    try {
      const b = await getBrowser();
      page = await b.newPage();
      await page.setViewport({ width: 1920, height: 1080 });
      await page.setExtraHTTPHeaders({
        'Accept-Language': 'fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,es;q=0.6,it;q=0.5,de;q=0.5'
      });

      const response = await page.goto(targetUrl, {
        waitUntil: 'domcontentloaded',
        timeout: 35000
      });

      // défi (Cloudflare « Just a moment… », /.enodia/challenge) : jusqu'à 30 s pour qu'il se résolve
      await new Promise(r => setTimeout(r, 1000));
      await solveChallenge(page);
      await new Promise(r => setTimeout(r, 1000));

      const title = await page.title();
      const content = await page.content();
      const status = response ? response.status() : 200;
      if (await onChallenge(page)) {
        // défi non résolu : ne pas renvoyer la page de défi comme si c'était le contenu demandé
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'défi anti-robot non résolu : ' + title, url: targetUrl }));
        return;
      }

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status, title, length: content.length, html: content }));
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: e.message, url: targetUrl }));
    } finally {
      if (page) await page.close().catch(() => {});
    }
  } else if (parsed.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'UP' }));
  } else {
    res.writeHead(404);
    res.end();
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Stealth Crawler Sidecar listening on http://${HOST}:${PORT} (navigateur : ${findBrowserPath()})`);
});
