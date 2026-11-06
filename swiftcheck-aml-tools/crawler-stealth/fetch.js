const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const fs = require('fs');

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

async function fetchPage(url, outPath, maxWaitSec = 10) {
  const browserPath = findBrowserPath();
  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-blink-features=AutomationControlled',
      '--window-size=1920,1080'
    ]
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    await page.setExtraHTTPHeaders({
      'Accept-Language': 'fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,es;q=0.6,it;q=0.5,de;q=0.5'
    });

    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 35000 });

    // Handle interstitials / challenges
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

    for (let i = 0; i < maxWaitSec; i++) {
      const title = (await page.title()).toLowerCase();
      const needsWait = challengeKeywords.some(kw => title.includes(kw));
      if (!needsWait && i >= 1) {
        break;
      }
      // Check for turnstile iframe
      const turnstileFrame = page.frames().find(f => f.url().includes('challenge-platform') || f.url().includes('turnstile'));
      if (turnstileFrame) {
        try {
          const btn = await turnstileFrame.$('input[type="checkbox"], #challenge-stage, .ctp-checkbox-label, label');
          if (btn) await btn.click();
        } catch (e) {}
      }
      await new Promise(r => setTimeout(r, 1000));
    }

    // Additional small grace period for dynamic rendering
    await new Promise(r => setTimeout(r, 1000));

    const finalTitle = await page.title();
    const content = await page.content();
    const status = response ? response.status() : 200;

    if (outPath) {
      fs.writeFileSync(outPath, content, 'utf8');
    }

    const result = {
      status,
      title: finalTitle,
      length: content.length,
      outPath: outPath || null
    };

    console.log(JSON.stringify(result));
    process.exit(0);
  } catch (err) {
    console.error(JSON.stringify({ error: err.message, url }));
    process.exit(1);
  } finally {
    await browser.close().catch(() => {});
  }
}

const targetUrl = process.argv[2];
const outPath = process.argv[3];
const waitSec = parseInt(process.argv[4] || '10', 10);

if (!targetUrl) {
  console.error(JSON.stringify({ error: 'No URL provided' }));
  process.exit(1);
}

fetchPage(targetUrl, outPath, waitSec);
