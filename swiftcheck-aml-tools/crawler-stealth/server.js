const http = require('http');
const urlParser = require('url');
const fs = require('fs');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const POSSIBLE_BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'
];

function findBrowserPath() {
  for (const p of POSSIBLE_BROWSERS) {
    if (fs.existsSync(p)) return p;
  }
  return 'msedge';
}

const PORT = 8099;
let browser = null;

async function getBrowser() {
  if (!browser || (typeof browser.isConnected === 'function' ? !browser.isConnected() : !browser.connected)) {
    browser = await puppeteer.launch({
      executablePath: findBrowserPath(),
      headless: 'new',
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
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

const server = http.createServer(async (req, res) => {
  const parsed = urlParser.parse(req.url, true);
  if (parsed.pathname === '/fetch' && parsed.query.url) {
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

      for (let i = 0; i < 10; i++) {
        const title = (await page.title()).toLowerCase();
        const needsWait = challengeKeywords.some(kw => title.includes(kw));
        if (!needsWait && i >= 1) break;
        const turnstileFrame = page.frames().find(f => f.url().includes('challenge-platform') || f.url().includes('turnstile'));
        if (turnstileFrame) {
          try {
            const btn = await turnstileFrame.$('input[type="checkbox"], #challenge-stage, .ctp-checkbox-label, label');
            if (btn) await btn.click();
          } catch (e) {}
        }
        await new Promise(r => setTimeout(r, 1000));
      }

      await new Promise(r => setTimeout(r, 1000));

      const title = await page.title();
      const content = await page.content();
      const status = response ? response.status() : 200;

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

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Stealth Crawler Sidecar listening on http://127.0.0.1:${PORT}`);
});
