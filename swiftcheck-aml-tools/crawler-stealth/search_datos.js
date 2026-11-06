const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

(async () => {
  const browser = await puppeteer.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  await page.goto('https://mptmd.gob.es/portal/politica-territorial/local/sistema_de_informacion_local_-sil-/alcaldes_y_concejales.html', { waitUntil: 'domcontentloaded' });
  const links = await page.$$eval('a', as => as.map(a => a.href));
  const files = [...new Set(links)].filter(h => h.match(/\.(xlsx|xls|csv|zip|pdf)/i));
  console.log('Files found: ' + files.length);
  files.forEach(f => console.log(f));
  await browser.close();
})();
