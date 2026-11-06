const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const EDGE_PATH = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function run() {
  const browser = await puppeteer.launch({
    executablePath: EDGE_PATH,
    headless: false,
    args: ['--window-size=1280,800', '--disable-blink-features=AutomationControlled']
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });
    console.log('Navigating...');
    await page.goto('https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/', {
      waitUntil: 'domcontentloaded',
      timeout: 30000
    });

    for (let i = 0; i < 20; i++) {
      await new Promise(r => setTimeout(r, 1000));
      const title = await page.title();
      console.log(`Sec ${i}: ${title}`);
      if (!title.includes('Just a moment') && !title.includes('Browser check')) {
        console.log('CLEARED! Title:', title);
        const content = await page.content();
        console.log('Length:', content.length);
        break;
      }

      // Check for turnstile iframe
      const turnstileFrame = page.frames().find(f => f.url().includes('challenges.cloudflare.com'));
      if (turnstileFrame) {
        try {
          const btn = await turnstileFrame.$('input[type="checkbox"], #challenge-stage, .ctp-checkbox-label, label');
          if (btn) {
            console.log('Found Turnstile element, clicking...');
            await btn.click();
          }
        } catch (e) {
          console.log('Error clicking:', e.message);
        }
      }
    }
  } finally {
    await browser.close();
  }
}

run().catch(console.error);

