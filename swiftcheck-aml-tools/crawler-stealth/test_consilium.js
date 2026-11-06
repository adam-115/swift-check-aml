const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const EDGE_PATH = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function testConsilium() {
  const browser = await puppeteer.launch({
    executablePath: EDGE_PATH,
    headless: false,
    args: ['--window-position=-2000,-2000']
  });
  const page = await browser.newPage();
  console.log('Navigating...');
  await page.goto('https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/', { waitUntil: 'networkidle2' });
  
  for (let i = 0; i < 15; i++) {
    await new Promise(r => setTimeout(r, 1000));
    const title = await page.title();
    console.log(`Sec ${i}: title = "${title}"`);
    if (!title.includes('Browser check') && !title.includes('Just a moment')) {
      break;
    }
  }

  const html = await page.content();
  console.log('Final Title:', await page.title());
  console.log('Final HTML length:', html.length);
  await browser.close();
}
testConsilium().catch(console.error);
